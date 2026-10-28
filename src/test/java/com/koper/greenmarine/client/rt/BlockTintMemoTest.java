package com.koper.greenmarine.client.rt;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BlockTintMemoTest {
 @Test void resolvesEachTintIndexOnceWithinABlockAndResetsForTheNextPosition() {
  var memo=new BlockTintMemo();var calls=new AtomicInteger();var location=new AtomicInteger(100);
  java.util.function.IntUnaryOperator lookup=id->{calls.incrementAndGet();return location.get()+id;};
  for(int face=0;face<6;face++){assertEquals(100,memo.colour(0,lookup));assertEquals(102,memo.colour(2,lookup));}
  assertEquals(2,calls.get());location.set(200);memo.reset();assertEquals(200,memo.colour(0,lookup));assertEquals(3,calls.get());
 }
 @Test void supportsManyIndicesZeroColoursAndDoesNotCacheFailedLookups() {
  var memo=new BlockTintMemo();for(int id=-2;id<10;id++)assertEquals(0,memo.colour(id,i->0));
  for(int id=-2;id<10;id++)assertEquals(0,memo.colour(id,i->{fail("cached colour must include zero");return 1;}));
  assertThrows(IllegalStateException.class,()->memo.colour(25,i->{throw new IllegalStateException();}));
  assertEquals(17,memo.colour(25,i->17));
 }
}
