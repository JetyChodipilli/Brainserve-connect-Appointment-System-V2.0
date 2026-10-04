#!/usr/bin/env node
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
const directory = mkdtempSync(join(tmpdir(), 'brainserve-work-metrics-'));
const harness = `import com.brainserve.appointment.worktask.application.WorkMetricCalculator;
import java.time.*;
import java.util.*;
public class WorkMetricChecks {
 static int checks;
 static void check(boolean condition,String name){checks++;if(!condition)throw new AssertionError(name);}
 static void same(Double actual,double expected,String name){check(actual!=null&&Math.abs(actual-expected)<0.000001,name);}
 public static void main(String[] args){
  check(WorkMetricCalculator.percentile(List.of(),.95)==null,"no invented empty percentile");
  same(WorkMetricCalculator.percentile(List.of(0d),.95),0,"zero duration retained");
  same(WorkMetricCalculator.percentile(List.of(10d),.5),10,"single observation");
  same(WorkMetricCalculator.percentile(List.of(0d,100d),.95),95,"continuous p95");
  same(WorkMetricCalculator.percentile(List.of(100d,0d),.5),50,"sort observations");
  same(WorkMetricCalculator.percentile(List.of(0d,10d,20d),.95),19,"p95 interpolation");
  same(WorkMetricCalculator.percentile(Arrays.asList(null,-100d,0d,Double.NaN,Double.POSITIVE_INFINITY,10d),.95),9.5,"invalid durations excluded");
  check(WorkMetricCalculator.percentile(List.of(-1d),.95)==null,"no negative observation");
  for(double invalid:List.of(-.1,1.1,Double.NaN,Double.POSITIVE_INFINITY))try{WorkMetricCalculator.percentile(List.of(1d),invalid);throw new AssertionError();}catch(IllegalArgumentException ok){checks++;}
  same(WorkMetricCalculator.percentile(List.of(0d,10d),0),0,"minimum percentile");
  same(WorkMetricCalculator.percentile(List.of(0d,10d),1),10,"maximum percentile");
  check(WorkMetricCalculator.rate(0,0)==null,"unknown empty rate");
  same(WorkMetricCalculator.rate(0,10),0,"unfinished denominator retained");
  same(WorkMetricCalculator.rate(2,4),50,"cohort rate");
  same(WorkMetricCalculator.rate(4,4),100,"whole cohort");
  try{WorkMetricCalculator.rate(2,1);throw new AssertionError();}catch(IllegalArgumentException ok){checks++;}
  ZoneId zone=ZoneId.of("Asia/Kolkata");LocalDate due=LocalDate.of(2026,10,4);Instant cutoff=due.plusDays(1).atStartOfDay(zone).toInstant();
  check(WorkMetricCalculator.acceptedOnTime(cutoff.minusNanos(1),due,zone,cutoff),"strict original office cutoff");
  check(!WorkMetricCalculator.acceptedOnTime(cutoff,due,zone,cutoff),"exact cutoff late");
  check(!WorkMetricCalculator.acceptedOnTime(cutoff.plusNanos(1),due,zone,cutoff.plusSeconds(1)),"after cutoff late");
  check(!WorkMetricCalculator.acceptedOnTime(cutoff.minusSeconds(1),due,zone,cutoff.minusSeconds(2)),"future acceptance excluded");
  check(!WorkMetricCalculator.acceptedOnTime(null,due,zone,cutoff),"unfinished acceptance");
  check(!WorkMetricCalculator.acceptedOnTime(cutoff,null,zone,cutoff),"unknown original excluded");
  for(String input:List.of("=SUM(A1:A2)","+1","-1","@SUM(A1)"," =1","\\tformula","\\rformula","\\nformula"))
   check(WorkMetricCalculator.csv(input).startsWith("\\\"'"),"formula/control neutralized "+input);
  check(WorkMetricCalculator.csv("a,b").equals("\\\"a,b\\\""),"comma quoted");
  check(WorkMetricCalculator.csv("a\\\"b").equals("\\\"a\\\"\\\"b\\\""),"quote escaped");
  check(WorkMetricCalculator.csv(null).equals("\\\"\\\""),"null field");
  check(WorkMetricCalculator.csv("ordinary").equals("\\\"ordinary\\\""),"ordinary label");
  ZoneId dst=ZoneId.of("America/New_York");LocalDate spring=LocalDate.of(2026,3,8);Instant dstCutoff=spring.plusDays(1).atStartOfDay(dst).toInstant();
  check(Duration.between(spring.atStartOfDay(dst).toInstant(),dstCutoff).toHours()==23,"office calendar day respects DST");
  check(WorkMetricCalculator.acceptedOnTime(dstCutoff.minusSeconds(1),spring,dst,dstCutoff),"DST original cutoff");
  System.out.println("WORK_METRIC_CHECKS_PASSED assertions="+checks);
 }
}`;
try {
 writeFileSync(join(directory,'WorkMetricChecks.java'),harness);
 const source=resolve('backend/src/main/java/com/brainserve/appointment/worktask/application/WorkMetricCalculator.java');
 const args=['--release','17','-d',directory,source,join(directory,'WorkMetricChecks.java')];
 let compile=spawnSync('javac',args,{encoding:'utf8',timeout:30000});
 if(compile.error?.code==='ENOENT')compile=spawnSync('java',['-m','jdk.compiler/com.sun.tools.javac.Main',...args],{encoding:'utf8',timeout:30000});
 if(compile.error)throw compile.error;
 if(compile.status!==0)throw new Error(`Compiler failed (${compile.status})\n${compile.stderr}\n${compile.stdout}`);
 const run=spawnSync('java',['-cp',directory,'WorkMetricChecks'],{encoding:'utf8',timeout:30000});
 if(run.error)throw run.error;
 if(run.status!==0)throw new Error(run.stderr||run.stdout);
 process.stdout.write(run.stdout);
} finally { rmSync(directory,{recursive:true,force:true}); }
