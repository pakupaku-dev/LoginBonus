package jp.pakupaku.rewards;

import com.google.gson.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public abstract class RewardsPlugin extends JavaPlugin {
    private RewardsStore store;
    private ScheduledExecutorService worker;
    private JsonObject policy;
    private String role,server;
    private volatile String error="starting";
    private long lastLogged;
    protected abstract String role();
    @Override public void onEnable(){
        role=role();saveDefaultConfig();
        try{
            if(!getDataFolder().toPath().resolve("quests.json").toFile().exists())saveResource("quests.json",false);
            policy=readPolicy();
            var mysql=getConfig().getConfigurationSection("mysql");
            if(getConfig().getBoolean("inherit-worldstatesync-mysql",true))mysql=YamlConfiguration.loadConfiguration(getDataFolder().toPath().getParent().resolve("WorldStateSync/config.yml").toFile()).getConfigurationSection("mysql");
            store=new RewardsStore(Objects.requireNonNull(mysql),getConfig().getString("economy-database","relisheconomy"),getConfig().getString("currency","dollars"));
            server=getServer().getName()+":"+getServer().getPort();
            worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,getName()+"-MySQL");t.setDaemon(true);return t;});
            worker.scheduleWithFixedDelay(this::tick,0,Math.max(2,getConfig().getInt("poll-seconds",5)),TimeUnit.SECONDS);
            Objects.requireNonNull(getCommand(role.equals("quests")?"dailyquests":"loginbonus")).setExecutor((sender,command,label,args)->{
                if(args.length>0&&args[0].equals("publish")){
                    if(!sender.hasPermission("rewards.admin")){sender.sendMessage("管理者権限が必要です。");return true;}
                    try{reloadConfig();JsonObject replacement=readPolicy();worker.submit(()->{
                        try{store.publish(role,replacement);message(sender,List.of("全サーバーの今後の報酬設定を更新しました。確定済みの日の内容は保持します。"));}
                        catch(Exception e){message(sender,List.of("設定を保存できません。ログを確認してください。"));getLogger().log(Level.WARNING,"Policy publish failed",e);}
                    });}catch(Exception e){sender.sendMessage("設定が不正です: "+e.getMessage());}
                }else if(sender instanceof Player p){
                    String id=p.getUniqueId().toString();
                    worker.submit(()->{try{message(sender,store.status(role,id));}catch(Exception e){message(sender,List.of("報酬情報の取得待ちです。しばらくして再実行してください。"));}});
                }else sender.sendMessage(getName()+" 1.0.0 / "+server+" / "+error);
                return true;
            });
            getLogger().info("Shared MySQL rewards enabled for "+server+"; calendar Asia/Tokyo; automatic payouts");
        }catch(Exception e){getLogger().log(Level.SEVERE,"Rewards startup failed",e);getServer().getPluginManager().disablePlugin(this);}
    }
    private JsonObject readPolicy()throws Exception{
        JsonObject p=new JsonObject();
        if(role.equals("quests")){
            String source=Files.readString(getDataFolder().toPath().resolve("quests.json"),StandardCharsets.UTF_8);
            Rules.Quest[] quests=RewardsStore.GSON.fromJson(source,Rules.Quest[].class);Set<String> ids=new HashSet<>();
            Set<String> kinds=Set.of("block_break","mob_kill","statistic_change","fish_catch","smelt_collect","harvest","breed","tame","item_consume");
            if(quests.length<1||quests.length>10000)throw new IllegalArgumentException("1-10000 quests required");
            for(Rules.Quest q:quests){
                if(q.id()==null||!q.id().matches("[a-z0-9_]{1,80}")||!ids.add(q.id())||q.target()<1||q.target()>1000000000||q.reward().signum()<0||q.reward().compareTo(new BigDecimal("1000000000"))>0||q.reward().scale()>2||q.title()==null||q.title().length()>160||q.category()==null||q.subject()==null||!kinds.contains(q.kind()))throw new IllegalArgumentException("Invalid quest: "+q.id());
            }
            int count=getConfig().getInt("daily-count",16);if(count<1||count>100||count>quests.length)throw new IllegalArgumentException("Invalid daily-count");
            p.add("catalog",JsonParser.parseString(source));p.addProperty("count",count);
        }else{
            BigDecimal base=new BigDecimal(getConfig().getString("login.base","500")),step=new BigDecimal(getConfig().getString("login.per-streak-day","100"));
            if(base.signum()<0||step.signum()<0||base.scale()>2||step.scale()>2||base.compareTo(new BigDecimal("1000000"))>0||step.compareTo(new BigDecimal("1000000"))>0)throw new IllegalArgumentException("Invalid login amounts");
            p.addProperty("base",base);p.addProperty("step",step);JsonObject milestones=new JsonObject();
            var section=Objects.requireNonNull(getConfig().getConfigurationSection("login.milestones"));
            for(String key:section.getKeys(false)){
                int interval=Integer.parseInt(key);BigDecimal amount=new BigDecimal(section.getString(key));
                if(interval<2||amount.signum()<0||amount.scale()>2||amount.compareTo(new BigDecimal("1000000"))>0)throw new IllegalArgumentException("Invalid milestone");
                milestones.addProperty(key,amount);
            }
            p.add("milestones",milestones);
        }
        return p;
    }
    private void message(org.bukkit.command.CommandSender sender,List<String> lines){if(isEnabled())getServer().getScheduler().runTask(this,()->lines.forEach(sender::sendMessage));}
    private void tick(){
        try{
            store.initialize(role,policy);
            for(int i=0;i<10;i++)if(store.process(role)<250)break;
            int paid=store.payPending();if(paid>0)getLogger().info("Committed "+paid+" reward payouts");
            error="ok";store.heartbeat(getName(),server,error);
        }catch(Exception e){
            error=e.getClass().getSimpleName();
            try{store.heartbeat(getName(),server,error);}catch(Exception ignored){}
            if(System.currentTimeMillis()-lastLogged>60000){lastLogged=System.currentTimeMillis();getLogger().log(Level.WARNING,"Rewards paused; queue and unpaid rewards retained for retry",e);}
        }
    }
    @Override public void onDisable(){
        if(worker!=null){worker.shutdown();try{if(!worker.awaitTermination(10,TimeUnit.SECONDS))worker.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();worker.shutdownNow();}}
        if(store!=null)store.close();
    }
}
