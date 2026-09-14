package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DonationPolicyTest {
  @org.junit.jupiter.api.Tag("storage")
  @Test
  void usefulIdleGiftsAreBoundedByExistingInventory() {
    assertEquals(12, DonationPolicy.desired("COAL", Map.of("COAL", 4), true));
    assertEquals(16, DonationPolicy.desired("BIRCH_LOG", Map.of(), true));
    assertEquals(0, DonationPolicy.desired("COAL", Map.of("COAL", 64), true));
  }

  @org.junit.jupiter.api.Tag("storage")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void activeWorkersDoNotCollectUnrelatedSpeculativeGifts() {
    assertEquals(0, DonationPolicy.desired("COAL", Map.of(), false));
    assertEquals(0, DonationPolicy.desired("DIAMOND_BLOCK", Map.of(), true));
  }

  @org.junit.jupiter.api.Tag("storage")
  @Test
  void retainFoodSeedsAndExistingTools() {
    assertEquals(0, DonationPolicy.desired("WOODEN_PICKAXE", Map.of("WOODEN_PICKAXE", 1), true));
    assertEquals(2, DonationPolicy.desired("WHEAT_SEEDS", Map.of("WHEAT_SEEDS", 6), true));
    assertEquals(0, RecipeCatalog.surplus(Map.of("WHEAT_SEEDS", 6), true).size());
  }
}
