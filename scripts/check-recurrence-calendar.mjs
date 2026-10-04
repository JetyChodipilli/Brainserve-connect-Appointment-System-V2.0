import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawnSync } from "node:child_process";

const scratch = await mkdtemp(join(tmpdir(), "brainserve-calendar-"));
const source = resolve("backend/src/main/java/com/brainserve/appointment/workroutine/application/RecurrenceCalendar.java");
const harness = `
import com.brainserve.appointment.workroutine.application.RecurrenceCalendar;
import com.brainserve.appointment.workroutine.application.RecurrenceCalendar.Definition;
import java.time.*;
import java.util.*;
public class CalendarCheck {
  static int checks;
  static LocalDate date(String value) { return LocalDate.parse(value); }
  static void equal(Object actual,Object expected) { checks++; if(!Objects.equals(actual,expected)) throw new AssertionError("Expected "+expected+" got "+actual); }
  static void yes(boolean value) { equal(value,true); }
  static void invalid(Runnable operation) { checks++; try { operation.run(); } catch(IllegalArgumentException expected) { return; } throw new AssertionError("Invalid calendar was accepted"); }
  static Definition d(String frequency,int interval,String start,String end,List<Integer> days,Integer month,String weekend,String holiday,List<LocalDate> holidays,String time,String zone) {
    return new Definition(frequency,interval,date(start),end==null?null:date(end),LocalTime.parse(time),days,month,weekend,holiday,holidays,ZoneId.of(zone));
  }
  public static void main(String[] args) {
    Definition daily=d("DAILY",3,"2024-01-31","2024-02-09",List.of(),null,"INCLUDE","INCLUDE",List.of(),"09:15","Asia/Kolkata");
    equal(RecurrenceCalendar.preview(daily,date("2024-01-01"),2,10).stream().map(o->o.occurrenceDate()).toList(),List.of(date("2024-01-31"),date("2024-02-03"),date("2024-02-06"),date("2024-02-09")));
    equal(RecurrenceCalendar.preview(daily,date("2024-02-01"),2,10).get(0).dueDate(),date("2024-02-05"));
    equal(RecurrenceCalendar.scheduledAt(daily,date("2024-01-31")),Instant.parse("2024-01-31T03:45:00Z"));
    yes(!RecurrenceCalendar.accepts(daily,date("2024-01-30")));
    yes(!RecurrenceCalendar.accepts(daily,date("2024-02-10")));
    Definition weekly=d("WEEKLY",2,"2024-01-03",null,List.of(1,4),null,"INCLUDE","INCLUDE",List.of(),"09:00","UTC");
    equal(RecurrenceCalendar.preview(weekly,date("2024-01-03"),0,4).stream().map(o->o.occurrenceDate()).toList(),List.of(date("2024-01-04"),date("2024-01-15"),date("2024-01-18"),date("2024-01-29")));
    yes(!RecurrenceCalendar.accepts(weekly,date("2024-01-08")));
    Definition monthly=d("MONTHLY",1,"2024-01-31",null,List.of(),31,"INCLUDE","INCLUDE",List.of(),"09:00","UTC");
    equal(RecurrenceCalendar.preview(monthly,date("2024-01-31"),0,4).stream().map(o->o.occurrenceDate()).toList(),List.of(date("2024-01-31"),date("2024-02-29"),date("2024-03-31"),date("2024-04-30")));
    Definition normal=d("MONTHLY",1,"2025-01-31",null,List.of(),31,"INCLUDE","INCLUDE",List.of(),"09:00","UTC");
    equal(RecurrenceCalendar.nextDate(normal,date("2025-02-01")).orElseThrow(),date("2025-02-28"));
    equal(RecurrenceCalendar.nextDate(normal,date("2025-03-01")).orElseThrow(),date("2025-03-31"));
    Definition spaced=d("MONTHLY",2,"2024-01-18",null,List.of(),17,"INCLUDE","INCLUDE",List.of(),"09:00","UTC");
    equal(RecurrenceCalendar.nextDate(spaced,date("2024-01-18")).orElseThrow(),date("2024-03-17"));
    Definition exclusions=d("DAILY",1,"2024-03-01",null,List.of(),null,"SKIP","SKIP",List.of(date("2024-03-04")),"09:00","UTC");
    equal(RecurrenceCalendar.preview(exclusions,date("2024-03-01"),365,3).stream().map(o->o.occurrenceDate()).toList(),List.of(date("2024-03-01"),date("2024-03-05"),date("2024-03-06")));
    equal(RecurrenceCalendar.preview(exclusions,date("2024-03-01"),365,3).get(0).dueDate(),date("2025-03-01"));
    Definition includeHoliday=d("DAILY",1,"2024-03-02","2024-03-02",List.of(),null,"INCLUDE","INCLUDE",List.of(date("2024-03-02")),"09:00","UTC");
    equal(RecurrenceCalendar.preview(includeHoliday,date("2024-03-02"),0,10).size(),1);
    Definition impossible=d("WEEKLY",1,"2024-01-01",null,List.of(6,7),null,"SKIP","INCLUDE",List.of(),"09:00","UTC");
    equal(RecurrenceCalendar.nextDate(impossible,date("2024-01-01")),Optional.empty());
    Definition gap=d("DAILY",1,"2024-03-10",null,List.of(),null,"INCLUDE","INCLUDE",List.of(),"02:30","America/New_York");
    equal(RecurrenceCalendar.scheduledAt(gap,date("2024-03-10")),Instant.parse("2024-03-10T07:30:00Z"));
    equal(RecurrenceCalendar.scheduledAt(gap,date("2024-03-11")),Instant.parse("2024-03-11T06:30:00Z"));
    Definition overlap=d("DAILY",1,"2024-11-03",null,List.of(),null,"INCLUDE","INCLUDE",List.of(),"01:30","America/New_York");
    equal(RecurrenceCalendar.scheduledAt(overlap,date("2024-11-03")),Instant.parse("2024-11-03T05:30:00Z"));
    equal(RecurrenceCalendar.preview(overlap,date("2024-11-03"),0,2).size(),2);
    Definition berlin=d("DAILY",1,"2024-03-31",null,List.of(),null,"INCLUDE","INCLUDE",List.of(),"02:30","Europe/Berlin");
    equal(RecurrenceCalendar.scheduledAt(berlin,date("2024-03-31")),Instant.parse("2024-03-31T01:30:00Z"));
    yes(RecurrenceCalendar.accepts(monthly,date("2034-01-31")));
    equal(RecurrenceCalendar.nextDate(monthly,date("2034-01-01")).orElseThrow(),date("2034-01-31"));
    equal(RecurrenceCalendar.preview(monthly,date("2034-01-01"),0,10).size(),10);
    invalid(()->d("DAILY",0,"2024-01-01",null,List.of(),null,"INCLUDE","INCLUDE",List.of(),"09:00","UTC"));
    invalid(()->d("DAILY",13,"2024-01-01",null,List.of(),null,"INCLUDE","INCLUDE",List.of(),"09:00","UTC"));
    invalid(()->d("YEARLY",1,"2024-01-01",null,List.of(),null,"INCLUDE","INCLUDE",List.of(),"09:00","UTC"));
    invalid(()->d("WEEKLY",1,"2024-01-01",null,List.of(),null,"INCLUDE","INCLUDE",List.of(),"09:00","UTC"));
    invalid(()->d("WEEKLY",1,"2024-01-01",null,List.of(0),null,"INCLUDE","INCLUDE",List.of(),"09:00","UTC"));
    invalid(()->d("WEEKLY",1,"2024-01-01",null,List.of(1,1),null,"INCLUDE","INCLUDE",List.of(),"09:00","UTC"));
    invalid(()->d("WEEKLY",1,"2024-01-01",null,Arrays.asList((Integer)null),null,"INCLUDE","INCLUDE",List.of(),"09:00","UTC"));
    invalid(()->d("DAILY",1,"2024-01-01",null,List.of(),null,"INCLUDE","INCLUDE",Arrays.asList((LocalDate)null),"09:00","UTC"));
    invalid(()->d("MONTHLY",1,"2024-01-01",null,List.of(),0,"INCLUDE","INCLUDE",List.of(),"09:00","UTC"));
    invalid(()->d("MONTHLY",1,"2024-01-01",null,List.of(),32,"INCLUDE","INCLUDE",List.of(),"09:00","UTC"));
    invalid(()->d("DAILY",1,"2024-01-02","2024-01-01",List.of(),null,"INCLUDE","INCLUDE",List.of(),"09:00","UTC"));
    invalid(()->d("DAILY",1,"2024-01-01","2029-01-02",List.of(),null,"INCLUDE","INCLUDE",List.of(),"09:00","UTC"));
    invalid(()->d("DAILY",1,"2024-01-01",null,List.of(),null,"MOVE","INCLUDE",List.of(),"09:00","UTC"));
    invalid(()->d("DAILY",1,"2024-01-01",null,List.of(),null,"INCLUDE","FEED",List.of(),"09:00","UTC"));
    invalid(()->d("DAILY",1,"2024-01-01",null,List.of(),null,"INCLUDE","SKIP",List.of(date("2024-01-02"),date("2024-01-02")),"09:00","UTC"));
    invalid(()->d("DAILY",1,"2024-01-01",null,List.of(),null,"INCLUDE","SKIP",date("2024-01-01").datesUntil(date("2025-01-02")).toList(),"09:00","UTC"));
    invalid(()->d("DAILY",1,"2024-01-01",null,List.of(),null,"INCLUDE","INCLUDE",List.of(),"09:00:01","UTC"));
    invalid(()->RecurrenceCalendar.preview(monthly,date("2024-01-01"),-1,10));
    invalid(()->RecurrenceCalendar.preview(monthly,date("2024-01-01"),366,10));
    invalid(()->RecurrenceCalendar.preview(monthly,date("2024-01-01"),0,0));
    invalid(()->RecurrenceCalendar.preview(monthly,date("2024-01-01"),0,101));
    System.out.println("RECURRENCE_CALENDAR_VERIFIED "+checks+" actual assertions");
  }
}
`;
try {
  const file = join(scratch, "CalendarCheck.java");
  await writeFile(file, harness);
  for (const [command, args] of [["javac", ["--release", "17", "-d", scratch, source, file]], ["java", ["-ea", "-cp", scratch, "CalendarCheck"]]]) {
    let result = spawnSync(command, args, { encoding: "utf8", timeout: 30_000 });
    // Some development images contain the JDK compiler module without a javac launcher.
    if (command === "javac" && result.error?.code === "ENOENT")
      result = spawnSync("java", ["-m", "jdk.compiler/com.sun.tools.javac.Main", ...args], { encoding: "utf8", timeout: 30_000 });
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(`${command} failed\n${result.stderr}\n${result.stdout}`);
    if (result.stdout) process.stdout.write(result.stdout);
  }
} finally { await rm(scratch, { recursive: true, force: true }); }
