package jp.pakupaku.rewards;
import com.google.gson.*;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RewardsTest {
 static JsonObject loginPolicy(){return JsonParser.parseString("{\"base\":500,\"step\":100,\"milestones\":{\"7\":1000,\"30\":5000,\"100\":20000,\"365\":100000}}").getAsJsonObject();}
 @Test void calendarAndIncreasingStreakRewards(){
  assertEquals(LocalDate.of(2026,9,10),Rules.day(Instant.parse("2026-09-09T15:00:00Z").toEpochMilli()));
  assertEquals(LocalDate.of(2026,9,9),Rules.day(Instant.parse("2026-09-09T14:59:59Z").toEpochMilli()));
  assertEquals(new BigDecimal("500.00"),Rules.loginReward(1,loginPolicy()));
  assertEquals(new BigDecimal("2100.00"),Rules.loginReward(7,loginPolicy()));
  for(int n=2;n<=1000;n++)assertTrue(Rules.loginReward(n,loginPolicy()).compareTo(Rules.loginReward(n-1,loginPolicy()))>0);
 }
 @Test void boardsAreStableAndVaried()throws Exception{
  var catalog=Arrays.asList(RewardsStore.GSON.fromJson(new String(getClass().getResourceAsStream("/quests.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),Rules.Quest[].class));
  assertEquals(215,catalog.size());LocalDate day=LocalDate.of(2026,9,10);
  var board=Rules.board(day,catalog,16);assertEquals(16,board.size());assertEquals(board,Rules.board(day,catalog,16));assertNotEquals(board,Rules.board(day.plusDays(1),catalog,16));
  assertEquals("mine_stone_100",board.getFirst().id());assertEquals(5,board.stream().map(Rules.Quest::category).distinct().count());
 }
 @Test void mysqlConcurrencyRecoveryAndLateLogins()throws Exception{
  Assumptions.assumeTrue(System.getenv("DPS_TEST_DB")!=null);
  MemoryConfiguration cfg=new MemoryConfiguration();
  for(String[] v:new String[][]{{"host","DPS_TEST_HOST"},{"database","DPS_TEST_DB"},{"username","DPS_TEST_USER"},{"password","DPS_TEST_PASSWORD"}})cfg.set(v[0],System.getenv(v[1]));cfg.set("port",Integer.parseInt(System.getenv("DPS_TEST_PORT")));
  String player=UUID.randomUUID().toString(),db=cfg.getString("database");
  try(RewardsStore a=new RewardsStore(cfg,db,"dollars");RewardsStore b=new RewardsStore(cfg,db,"dollars");Connection c=a.connection()){
   JsonObject policy=new JsonObject();policy.addProperty("count",1);policy.add("catalog",RewardsStore.GSON.toJsonTree(List.of(new Rules.Quest("mine_stone_100","採掘","石100","block_break","minecraft:stone",100,new BigDecimal("500")))));
   a.initialize("quests",policy);b.initialize("login",loginPolicy());RewardsStore.update(c,"UPDATE reward_settings SET value='0' WHERE setting_key='activation'");
   RewardsStore.update(c,"CREATE TABLE balances(uuid VARCHAR(36),currency VARCHAR(50),balance DOUBLE,discord_id VARCHAR(60),last_modified TIMESTAMP NULL,PRIMARY KEY(uuid,currency)) ENGINE=InnoDB");
   RewardsStore.update(c,"CREATE TABLE detailed_stats_events(event_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,player_uuid CHAR(36),server_id VARCHAR(96),occurred_ms BIGINT,kind VARCHAR(48),subject VARCHAR(128),amount DECIMAL(24,6),world_uuid CHAR(36),x INT,y INT,z INT,game_mode VARCHAR(24),context JSON) ENGINE=InnoDB");
   RewardsStore.update(c,"INSERT INTO balances VALUES(?,'dollars',1000,NULL,NOW())",player);
   long t=Instant.parse("2026-09-10T03:00:00Z").toEpochMilli();
   for(int x=0;x<99;x++)event(c,player,t,"block_break",x,"SURVIVAL");
   event(c,player,t,"block_break",0,"SURVIVAL"); // repeat position
   event(c,player,t,"block_break",99,"CREATIVE");
   event(c,player,t-1,"block_place",100,"SURVIVAL");event(c,player,t,"block_break",100,"SURVIVAL");
   a.process("quests");assertEquals(99,number(c,"SELECT progress FROM reward_progress"));assertEquals(0,number(c,"SELECT COUNT(*) FROM reward_grants"));
   event(c,player,t,"block_break",101,"SURVIVAL");
   try(var executor=Executors.newFixedThreadPool(2)){
    var f1=executor.submit(()->a.process("quests"));var f2=executor.submit(()->b.process("quests"));f1.get();f2.get();
   }
   assertEquals(100,number(c,"SELECT progress FROM reward_progress"));assertEquals(1,number(c,"SELECT COUNT(*) FROM reward_grants"));
   assertThrows(SQLException.class,a::payPending); // missing transaction ledger must roll back wallet
   assertEquals(1000,number(c,"SELECT balance FROM balances"));assertEquals(0,number(c,"SELECT paid_amount FROM reward_grants"));
   RewardsStore.update(c,"CREATE TABLE transactions(id INT AUTO_INCREMENT PRIMARY KEY,uuid VARCHAR(36),discord_id VARCHAR(60),currency VARCHAR(50),amount DOUBLE,old_balance DOUBLE,new_balance DOUBLE,transaction_type VARCHAR(50),reason TEXT) ENGINE=InnoDB");
   try(var executor=Executors.newFixedThreadPool(2)){
    var f1=executor.submit(a::payPending);var f2=executor.submit(b::payPending);f1.get();f2.get();
   }
   assertEquals(1500,number(c,"SELECT balance FROM balances"));assertEquals(1,number(c,"SELECT COUNT(*) FROM transactions"));
   event(c,player,t,"join",0,"SURVIVAL");event(c,player,t,"join",0,"SURVIVAL");event(c,player,t+86400000L*2,"join",0,"SURVIVAL");
   b.process("login");b.payPending();assertEquals(2500,number(c,"SELECT balance FROM balances"));
   event(c,player,t+86400000L,"join",0,"SURVIVAL");b.process("login");b.payPending();
   assertEquals(3300,number(c,"SELECT balance FROM balances"));assertEquals(3,number(c,"SELECT MAX(streak) FROM reward_login_days"));assertEquals(3,number(c,"SELECT COUNT(*) FROM reward_login_days"));
   event(c,player,t+86400000L*4,"join",0,"SURVIVAL");b.process("login");b.payPending();
   assertEquals(3800,number(c,"SELECT balance FROM balances"));
   assertEquals(1,number(c,"SELECT streak FROM reward_login_days ORDER BY day DESC LIMIT 1"));
   assertEquals(0,number(c,"SELECT COUNT(*) FROM reward_login_queue"));assertEquals(0,number(c,"SELECT COUNT(*) FROM reward_quest_queue"));
  }
 }
 static long number(Connection c,String sql)throws SQLException{try(var p=c.createStatement();var r=p.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
 static void event(Connection c,String player,long time,String kind,int x,String mode)throws SQLException{
  String id=UUID.randomUUID().toString();RewardsStore.update(c,"INSERT INTO detailed_stats_events VALUES(?,?, 'Paper:25566',?,?, 'minecraft:stone',1,'00000000-0000-0000-0000-000000000000',?,64,0,?,'{}')",id,player,time,kind,x,mode);
  RewardsStore.update(c,"INSERT INTO "+(kind.equals("join")?"reward_login_queue":"reward_quest_queue")+"(event_id) VALUES(?)",id);
 }
}
