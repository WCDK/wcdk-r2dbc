package com.wcdk.r2dbc.id;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
class SnowflakeRuntimeTests {
 private static final long NOW=1800000000000L;
 @Test void validatesRanges(){
  for(long node:new long[]{-1,1024}) assertThatThrownBy(()->new SnowflakeIdGenerator(node)).isInstanceOf(IllegalArgumentException.class);
  assertThat(new SnowflakeIdGenerator(1023,()->NOW).getWorkerId()).isEqualTo(1023);
  for(long time:new long[]{0,Long.MAX_VALUE}) assertThatThrownBy(()->new SnowflakeIdGenerator(1,()->time).nextId()).isInstanceOf(IllegalStateException.class);
 }
 @Test void rollbackFailsImmediatelyAndRecovers(){
  var clock=new AtomicLong(NOW); var g=new SnowflakeIdGenerator(1,clock::get); long first=g.nextId();
  for(long offset:new long[]{1,1001}){
   clock.set(NOW-offset);
   org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1),()->assertThatThrownBy(g::nextId).hasMessageContaining("时钟回拨"));
  }
  clock.set(NOW); assertThat(g.nextId()).isEqualTo(first+1);
 }
 @Test void overflowWaitsAndAdvancesWithoutDuplicates() throws Exception {
  var clock=new AtomicLong(NOW); var g=new SnowflakeIdGenerator(2,clock::get); var ids=new HashSet<Long>();
  for(int i=0;i<4096;i++) assertThat(ids.add(g.nextId())).isTrue();
  assertThat(ids.stream().mapToLong(id->id&4095).max().orElseThrow()).isEqualTo(4095);
  try(var pool=Executors.newSingleThreadExecutor()){
   var waiting=pool.submit(g::nextId);
   assertThatThrownBy(()->waiting.get(50,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
   clock.incrementAndGet(); long next=waiting.get(1,TimeUnit.SECONDS);
   assertThat(ids.add(next)).isTrue(); assertThat(next&4095).isZero();
  }
 }
 @Test void waitingObservesRollbackAndInterruption() throws Exception {
  var clock=new AtomicLong(NOW); var g=new SnowflakeIdGenerator(3,clock::get);
  for(int i=0;i<4096;i++)g.nextId();
  try(var pool=Executors.newSingleThreadExecutor()){
   var waiting=pool.submit(g::nextId);
   assertThatThrownBy(()->waiting.get(50,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
   clock.decrementAndGet(); assertThatThrownBy(()->waiting.get(1,TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
  }
  clock.set(NOW); Thread.currentThread().interrupt();
  try{assertThatThrownBy(g::nextId).hasMessageContaining("中断");}finally{Thread.interrupted();}
 }
 @Test void concurrentSameNodeInstancesRemainUnique() throws Exception {
  int threads=8,each=20000; Set<Long> ids=ConcurrentHashMap.newKeySet(); var work=new ArrayList<Future<?>>();
  var start=new CountDownLatch(1); long begin=System.nanoTime();
  try(var pool=Executors.newFixedThreadPool(threads)){
   for(int t=0;t<threads;t++){
    var g=new SnowflakeIdGenerator(1022);
    work.add(pool.submit(()->{try{start.await();}catch(InterruptedException e){throw new RuntimeException(e);}
     for(int i=0;i<each;i++)if(!ids.add(g.nextId()))throw new AssertionError("重复 ID");}));
   }
   start.countDown(); for(var f:work)f.get(15,TimeUnit.SECONDS);
  }
  assertThat(ids).hasSize(threads*each);
  System.out.printf("雪花并发唯一性：线程=%d，ID=%d，耗时=%.3fms%n",threads,ids.size(),(System.nanoTime()-begin)/1e6);
 }
 @Test void reportsThroughput() throws Exception {
  for(int threads:new int[]{1,8}){
   int each=20000; var g=new SnowflakeIdGenerator(1021); for(int i=0;i<10000;i++)g.nextId();
   var ready=new CountDownLatch(threads);var start=new CountDownLatch(1); var work=new ArrayList<Future<Long>>();long elapsed;
   try(var pool=Executors.newFixedThreadPool(threads)){
    for(int t=0;t<threads;t++)work.add(pool.submit(()->{ready.countDown();start.await();long last=0;
     for(int i=0;i<each;i++)last=g.nextId();return last;}));
    assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();long begin=System.nanoTime();start.countDown();
    for(var f:work)assertThat(f.get(15,TimeUnit.SECONDS)).isPositive();elapsed=System.nanoTime()-begin;
   }
   System.out.printf("雪花 CAS 吞吐：线程=%d，ID=%d，耗时=%.3fms，ID/s=%.0f%n",threads,threads*each,elapsed/1e6,threads*each*1e9/elapsed);
  }
 }
 @Test void resolvesEnvironmentAndRejectsMalformedValues(){
  assertThat(new EnvironmentWorkerIdResolver(key->" 12 ").resolveWorkerId()).isEqualTo(12);
  assertThatThrownBy(()->new EnvironmentWorkerIdResolver(key->"abc").resolveWorkerId()).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->new EnvironmentWorkerIdResolver(key->null).resolveWorkerId()).hasMessageContaining("WCDK_R2DBC_WORKER_ID");
 }
}
