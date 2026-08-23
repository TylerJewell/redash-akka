package io.akka.redash.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R8 and R9. The hexadecimal digests below are the ones the original printed for
 * the same fourteen texts (`probes/out/probe_01.json`), so this checks the value and not
 * only the equivalence — a rebuild that agreed about which texts match while producing its
 * own digests would file every cached result under a key redash cannot read.
 */
class QueryHashTest {

  private static final String BASE = "SELECT 1 FROM t WHERE c='Value'";
  private static final String BASE_HASH = "2ce06267eadbc7cfd4fda2a92ebaa28f";

  @Test
  void theDigestIsTheOnesTheOriginalProduces() {
    assertEquals(BASE_HASH, QueryHash.of(BASE));
    assertEquals("252c25d43cff89f5cdb660f053a86eac", QueryHash.of("/* a\nb */" + BASE));
    assertEquals("a46d4fb20a344398de39aa0307864016", QueryHash.of("select 1 from t where c='Value'"));
    assertEquals("414d11f96fbc2e172d74ed6c74db5f45", QueryHash.of("SELECT 1 FROM t WHERE c='value'"));
    assertEquals("289e81f0bf7f5a2f44648d6a1617f368", QueryHash.of("-- daily\n" + BASE));
    assertEquals("080967ae613a5165d1cc6d83069f5463", QueryHash.of("/* dailySELECT 1 FROM t WHERE c='Value'"));
    assertEquals("eeb2cf2075b92bbac83cae4f044ae4ca", QueryHash.of(BASE + ";"));
  }

  @Test
  void singleLineBlockCommentsAreErased() {
    assertEquals(BASE_HASH, QueryHash.of("/* daily */" + BASE));
    assertEquals(BASE_HASH, QueryHash.of("SELECT 1 /* here */FROM t WHERE c='Value'"));
    assertEquals(BASE_HASH, QueryHash.of("/*a*/SELECT 1 /*b*/FROM t WHERE c='Value'"));
  }

  @Test
  void everyKindOfWhitespaceIsErased() {
    assertEquals(BASE_HASH, QueryHash.of("SELECT   1 FROM  t WHERE c='Value'"));
    assertEquals(BASE_HASH, QueryHash.of("SELECT 1\nFROM t\nWHERE c='Value'"));
    assertEquals(BASE_HASH, QueryHash.of("SELECT\t1 FROM t WHERE c='Value'"));
    assertEquals(BASE_HASH, QueryHash.of("SELECT1FROMtWHEREc='Value'"));
  }

  @Test
  void aBlockCommentSpanningLinesIsNotErased() {
    assertNotEquals(BASE_HASH, QueryHash.of("/* a\nb */" + BASE));
  }

  @Test
  void caseCommentsAndPunctuationAreNotErased() {
    assertNotEquals(BASE_HASH, QueryHash.of("select 1 from t where c='Value'"));
    assertNotEquals(BASE_HASH, QueryHash.of("SELECT 1 FROM t WHERE c='value'"));
    assertNotEquals(BASE_HASH, QueryHash.of("-- daily\n" + BASE));
    assertNotEquals(BASE_HASH, QueryHash.of("/* dailySELECT 1 FROM t WHERE c='Value'"));
    assertNotEquals(BASE_HASH, QueryHash.of(BASE + ";"));
  }

  @Test
  void theCacheKeyIsTheHashAndTheDataSource() {
    assertEquals(BASE_HASH + ":ds-1", QueryHash.cacheKey(QueryHash.of(BASE), "ds-1"));
    assertNotEquals(
        QueryHash.cacheKey(QueryHash.of(BASE), "ds-1"),
        QueryHash.cacheKey(QueryHash.of(BASE), "ds-2"));
  }
}
