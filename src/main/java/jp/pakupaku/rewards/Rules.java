package jp.pakupaku.rewards;

import com.google.gson.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class Rules {
    public static final ZoneId ZONE=ZoneId.of("Asia/Tokyo");
    public record Quest(String id,String category,String title,String kind,String subject,long target,BigDecimal reward) {}
    public static LocalDate day(long millis){return Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate();}
    public static BigDecimal loginReward(int streak,JsonObject policy){
        BigDecimal total=policy.get("base").getAsBigDecimal().add(policy.get("step").getAsBigDecimal().multiply(BigDecimal.valueOf(streak-1)));
        for(var m:policy.getAsJsonObject("milestones").entrySet())total=total.add(m.getValue().getAsBigDecimal().multiply(BigDecimal.valueOf(streak/Integer.parseInt(m.getKey()))));
        return total.setScale(2);
    }
    public static List<Quest> board(LocalDate day,List<Quest> catalog,int count){
        List<Quest> result=new ArrayList<>();
        catalog.stream().filter(q->q.id().equals("mine_stone_100")).findFirst().ifPresent(result::add);
        Map<String,List<Quest>> groups=new TreeMap<>();
        for(Quest q:catalog)if(!result.contains(q))groups.computeIfAbsent(q.category(),k->new ArrayList<>()).add(q);
        Random random=new Random(day.toEpochDay() ^ 0x50414B5550414B55L);
        groups.values().forEach(list->Collections.shuffle(list,random));
        while(result.size()<Math.min(count,catalog.size())){
            boolean added=false;
            for(var group:groups.values())if(!group.isEmpty()&&result.size()<count){result.add(group.removeFirst());added=true;}
            if(!added)break;
        }
        return result;
    }
    public static boolean matches(Quest q,JsonObject event){
        return q.kind().equals(event.get("kind").getAsString()) && (q.subject().equals("*") || q.subject().equals(event.get("subject").getAsString()));
    }
}
