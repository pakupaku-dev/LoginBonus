package jp.kamei.rewards;

import com.google.gson.*;
import com.zaxxer.hikari.*;
import org.bukkit.configuration.ConfigurationSection;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class RewardsStore implements AutoCloseable {
    static final Gson GSON=new Gson();
    final HikariDataSource pool;
    final String economy,currency;
    private boolean initialized;
    public RewardsStore(ConfigurationSection mysql,String economy,String currency){
        String db=mysql.getString("database","minecraft");
        if(!db.matches("[A-Za-z0-9_]+")||!economy.matches("[A-Za-z0-9_]+")||!currency.matches("[a-z0-9_]+"))throw new IllegalArgumentException("Invalid database or currency");
        this.economy=economy;this.currency=currency;
        HikariConfig h=new HikariConfig();
        h.setJdbcUrl("jdbc:mysql://"+mysql.getString("host")+":"+mysql.getInt("port",3306)+"/"+db+"?useSSL="+mysql.getBoolean("use-ssl",false)+"&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        h.setUsername(mysql.getString("username"));h.setPassword(mysql.getString("password"));h.setMaximumPoolSize(2);h.setMinimumIdle(0);h.setConnectionTimeout(5000);h.setInitializationFailTimeout(-1);
        pool=new HikariDataSource(h);
    }
    public Connection connection() throws SQLException{return pool.getConnection();}
    public synchronized void initialize(String role,JsonObject policy) throws Exception{
        if(initialized)return;
        try(Connection c=connection();Statement s=c.createStatement()){
            String ddl=new String(getClass().getResourceAsStream("/rewards-schema.sql").readAllBytes(),StandardCharsets.UTF_8);
            for(String sql:ddl.split(";"))if(!sql.isBlank())s.execute(sql);
            update(c,"INSERT IGNORE INTO reward_locks VALUES(?)",role);
            update(c,"INSERT IGNORE INTO reward_settings VALUES(?,?)",role,policy.toString());
            update(c,"INSERT IGNORE INTO reward_settings VALUES('activation',?)",Long.toString(System.currentTimeMillis()));
            JsonObject wallet=new JsonObject();wallet.addProperty("database",economy);wallet.addProperty("currency",currency);
            update(c,"INSERT IGNORE INTO reward_settings VALUES('wallet',?)",wallet.toString());
            if(!setting(c,"wallet").equals(wallet))throw new SQLException("Shared reward wallet configuration differs across servers");
        }
        initialized=true;
    }
    public void publish(String role,JsonObject policy)throws SQLException{try(Connection c=connection()){update(c,"UPDATE reward_settings SET value=? WHERE setting_key=?",policy.toString(),role);}}
    static int update(Connection c,String sql,Object...values)throws SQLException{
        try(PreparedStatement p=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);return p.executeUpdate();}
    }
    static JsonElement setting(Connection c,String key)throws SQLException{
        try(PreparedStatement p=c.prepareStatement("SELECT value FROM reward_settings WHERE setting_key=?")){p.setString(1,key);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Missing rewards setting: "+key);return JsonParser.parseString(r.getString(1));}}
    }
    static void lock(Connection c,String role)throws SQLException{
        try(PreparedStatement p=c.prepareStatement("SELECT name FROM reward_locks WHERE name=? FOR UPDATE")){p.setString(1,role);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Missing worker lock");}}
    }
    List<Rules.Quest> board(Connection c,LocalDate day)throws SQLException{
        try(PreparedStatement p=c.prepareStatement("SELECT quests FROM reward_boards WHERE day=?")){
            p.setString(1,day.toString());try(ResultSet r=p.executeQuery()){if(r.next())return Arrays.asList(GSON.fromJson(r.getString(1),Rules.Quest[].class));}
        }
        JsonObject policy=setting(c,"quests").getAsJsonObject();
        List<Rules.Quest> selected=Rules.board(day,Arrays.asList(GSON.fromJson(policy.get("catalog"),Rules.Quest[].class)),policy.get("count").getAsInt());
        update(c,"INSERT INTO reward_boards VALUES(?,?,?)",day.toString(),GSON.toJson(selected),System.currentTimeMillis());
        return selected;
    }
    public int process(String role)throws Exception{
        String queue=role.equals("quests")?"reward_quest_queue":"reward_login_queue";
        try(Connection c=connection()){
            c.setAutoCommit(false);
            try{
                lock(c,role);
                long activation=setting(c,"activation").getAsLong();
                if(role.equals("quests"))board(c,Rules.day(System.currentTimeMillis()));
                List<JsonObject> events=new ArrayList<>();
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT q.seq,e.* FROM "+queue+" q JOIN detailed_stats_events e ON e.event_id=q.event_id ORDER BY q.seq LIMIT 250")){
                    while(r.next()){
                        JsonObject event=new JsonObject();
                        for(String name:new String[]{"seq","event_id","player_uuid","server_id","occurred_ms","kind","subject","amount","world_uuid","x","y","z","game_mode","context"})event.addProperty(name,r.getString(name));
                        events.add(event);
                    }
                }
                for(JsonObject event:events){
                    if(event.get("occurred_ms").getAsLong()>=activation){
                        if(role.equals("quests"))questEvent(c,event);else loginEvent(c,event);
                    }
                    update(c,"DELETE FROM "+queue+" WHERE seq=?",event.get("seq").getAsLong());
                }
                c.commit();return events.size();
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    private static String str(JsonObject e,String key){return e.get(key).getAsString();}
    private void questEvent(Connection c,JsonObject e)throws SQLException{
        String kind=str(e,"kind"),player=str(e,"player_uuid"),server=str(e,"server_id"),world=str(e,"world_uuid");
        int x=e.get("x").getAsInt(),y=e.get("y").getAsInt(),z=e.get("z").getAsInt();
        long time=e.get("occurred_ms").getAsLong();LocalDate day=Rules.day(time);
        if(kind.equals("block_place")){
            update(c,"INSERT INTO reward_placed_positions VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE placed_ms=LEAST(placed_ms,VALUES(placed_ms))",server,world,x,y,z,time);return;
        }
        if(!Set.of("SURVIVAL","ADVENTURE").contains(str(e,"game_mode")))return;
        List<Rules.Quest> matches=board(c,day).stream().filter(q->Rules.matches(q,e)).toList();
        if(matches.isEmpty())return;
        boolean miningAllowed=true;
        if(kind.equals("block_break")){
            try(PreparedStatement p=c.prepareStatement("SELECT placed_ms FROM reward_placed_positions WHERE server_id=? AND world_uuid=? AND x=? AND y=? AND z=?")){
                Object[] v={server,world,x,y,z};for(int i=0;i<v.length;i++)p.setObject(i+1,v[i]);
                try(ResultSet r=p.executeQuery()){if(r.next()&&r.getLong(1)<=time)miningAllowed=false;}
            }
            if(update(c,"INSERT IGNORE INTO reward_mined_positions VALUES(?,?,?,?,?,?,?)",day.toString(),player,server,world,x,y,z)==0)miningAllowed=false;
        }
        JsonObject context=JsonParser.parseString(str(e,"context")).getAsJsonObject();
        String material=str(e,"subject");
        if(kind.equals("block_break")&&Set.of("minecraft:wheat","minecraft:carrots","minecraft:potatoes","minecraft:beetroots").contains(material)){
            int mature=material.equals("minecraft:beetroots")?3:7;
            if(!context.has("block_data")||!context.get("block_data").getAsString().contains("age="+mature+"]"))return;
        }
        for(Rules.Quest q:matches){
            // Mining/ore rewards exclude observed player placements and repeated positions.
            // Farming/log regrowth remains repeatable; daily board targets bound payouts.
            if(q.category().equals("採掘")&&!miningAllowed)continue;
            long amount=Math.min(q.target(),Math.max(0,e.get("amount").getAsBigDecimal().longValue()));
            update(c,"INSERT INTO reward_progress VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE progress=LEAST(target,progress+VALUES(progress))",player,day.toString(),q.id(),Math.min(amount,q.target()),q.target());
            try(PreparedStatement p=c.prepareStatement("SELECT progress FROM reward_progress WHERE player_uuid=? AND day=? AND quest_id=?")){
                p.setString(1,player);p.setString(2,day.toString());p.setString(3,q.id());try(ResultSet r=p.executeQuery()){if(r.next()&&r.getLong(1)>=q.target())grant(c,"quest:"+player+":"+day+":"+q.id(),player,day,"quest",q.title(),q.reward());}
            }
        }
    }
    private void loginEvent(Connection c,JsonObject e)throws SQLException{
        String player=str(e,"player_uuid");LocalDate day=Rules.day(e.get("occurred_ms").getAsLong());
        JsonObject policy=setting(c,"login").getAsJsonObject();
        if(update(c,"INSERT IGNORE INTO reward_login_days VALUES(?,?,0,0,?)",player,day.toString(),policy.toString())==0)return;
        // Recompute in calendar order so late journals repair streaks and top up later rewards.
        record LoginDay(LocalDate day,JsonObject policy){}
        List<LoginDay> days=new ArrayList<>();
        try(PreparedStatement p=c.prepareStatement("SELECT day,policy FROM reward_login_days WHERE player_uuid=? ORDER BY day")){
            p.setString(1,player);try(ResultSet r=p.executeQuery()){while(r.next())days.add(new LoginDay(r.getDate(1).toLocalDate(),JsonParser.parseString(r.getString(2)).getAsJsonObject()));}
        }
        int streak=0;LocalDate previous=null;
        for(LoginDay d:days){
            streak=previous!=null&&previous.plusDays(1).equals(d.day())?streak+1:1;previous=d.day();
            BigDecimal amount=Rules.loginReward(streak,d.policy());
            update(c,"UPDATE reward_login_days SET streak=?,reward=? WHERE player_uuid=? AND day=?",streak,amount,player,d.day().toString());
            grant(c,"login:"+player+":"+d.day(),player,d.day(),"login","連続ログイン "+streak+"日目",amount);
        }
    }
    static void grant(Connection c,String id,String player,LocalDate day,String kind,String title,BigDecimal amount)throws SQLException{
        update(c,"INSERT INTO reward_grants(grant_id,player_uuid,day,kind,title,amount) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE paid_ms=IF(VALUES(amount)>amount,NULL,paid_ms),amount=GREATEST(amount,VALUES(amount)),title=VALUES(title)",id,player,day.toString(),kind,title,amount);
    }
    public int payPending()throws SQLException{
        List<String[]> pending=new ArrayList<>();
        try(Connection c=connection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT grant_id,player_uuid FROM reward_grants WHERE paid_ms IS NULL ORDER BY attempted_ms,day,grant_id LIMIT 100")){while(r.next())pending.add(new String[]{r.getString(1),r.getString(2)});}
        int paid=0;
        SQLException failure=null;
        for(String[] item:pending){
            try(Connection c=connection()){update(c,"UPDATE reward_grants SET attempted_ms=? WHERE grant_id=?",System.currentTimeMillis(),item[0]);}
            try{if(pay(item[0],item[1]))paid++;}catch(SQLException e){failure=e;}
        }
        if(failure!=null)throw failure;
        return paid;
    }
    boolean pay(String id,String player)throws SQLException{
        try(Connection c=connection()){
            c.setAutoCommit(false);
            try{
                BigDecimal old;String discord;
                // Every payer locks wallet before grant, matching the portal's wallet lock discipline.
                try(PreparedStatement p=c.prepareStatement("SELECT balance,discord_id FROM `"+economy+"`.balances WHERE uuid=? AND currency=? FOR UPDATE")){
                    p.setString(1,player);p.setString(2,currency);try(ResultSet r=p.executeQuery()){if(!r.next()){c.rollback();return false;}old=r.getBigDecimal(1);discord=r.getString(2);}
                }
                BigDecimal amount;String title,kind;
                try(PreparedStatement p=c.prepareStatement("SELECT amount-paid_amount,title,kind FROM reward_grants WHERE grant_id=? FOR UPDATE")){
                    p.setString(1,id);try(ResultSet r=p.executeQuery()){if(!r.next()){c.rollback();return false;}amount=r.getBigDecimal(1);title=r.getString(2);kind=r.getString(3);}
                }
                if(amount.signum()<=0){update(c,"UPDATE reward_grants SET paid_ms=COALESCE(paid_ms,?) WHERE grant_id=?",System.currentTimeMillis(),id);c.commit();return false;}
                BigDecimal next=old.add(amount);
                // Existing RelishEconomy uses DOUBLE; leave oversized balances pending instead of losing precision.
                if(next.compareTo(new BigDecimal("9000000000000"))>0)throw new SQLException("Wallet exceeds safe reward precision range");
                if(update(c,"UPDATE `"+economy+"`.balances SET balance=?,last_modified=CURRENT_TIMESTAMP WHERE uuid=? AND currency=?",next,player,currency)!=1)throw new SQLException("Wallet update failed");
                update(c,"INSERT INTO `"+economy+"`.transactions(uuid,discord_id,currency,amount,old_balance,new_balance,transaction_type,reason) VALUES(?,?,?,?,?,?,?,?)",player,discord,currency,amount,old,next,kind.equals("quest")?"DAILY_QUEST":"LOGIN_BONUS",id+" "+title);
                update(c,"UPDATE reward_grants SET paid_amount=amount,paid_ms=? WHERE grant_id=?",System.currentTimeMillis(),id);
                c.commit();return true;
            }catch(SQLException e){c.rollback();throw e;}
        }
    }
    public void heartbeat(String plugin,String server,String error)throws SQLException{
        try(Connection c=connection()){update(c,"INSERT INTO reward_servers VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE heartbeat_ms=VALUES(heartbeat_ms),last_error=VALUES(last_error)",plugin,server,System.currentTimeMillis(),error);}
    }
    public List<String> status(String role,String player)throws SQLException{
        List<String> lines=new ArrayList<>();LocalDate today=Rules.day(System.currentTimeMillis());
        try(Connection c=connection()){
            if(role.equals("quests")){
                c.setAutoCommit(false);lock(c,role);List<Rules.Quest> board=board(c,today);c.commit();
                Map<String,Long> progress=new HashMap<>();
                try(PreparedStatement p=c.prepareStatement("SELECT quest_id,progress FROM reward_progress WHERE player_uuid=? AND day=?")){p.setString(1,player);p.setString(2,today.toString());try(ResultSet r=p.executeQuery()){while(r.next())progress.put(r.getString(1),r.getLong(2));}}
                lines.add("§6本日のクエスト / "+today+" (日本時間)");
                for(Rules.Quest q:board)lines.add("§e"+q.title()+" §f"+progress.getOrDefault(q.id(),0L)+"/"+q.target()+" §a+"+q.reward()+" D");
            }else{
                try(PreparedStatement p=c.prepareStatement("SELECT day,streak,reward FROM reward_login_days WHERE player_uuid=? ORDER BY day DESC LIMIT 1")){
                    p.setString(1,player);try(ResultSet r=p.executeQuery()){
                        if(r.next())lines.add("§6最終ログイン日 "+r.getDate(1)+" / 連続 "+r.getInt(2)+"日 / 報酬 "+r.getBigDecimal(3)+" D");else lines.add("ログイン記録の反映をお待ちください。");
                    }
                }
            }
            lines.add("§7達成報酬は自動入金。詳細・入金状態はポータルで確認できます。");
        }
        return lines;
    }
    @Override public void close(){pool.close();}
}
