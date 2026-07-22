package io.github.ozozorz.aipartner.conversation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Verifies boundary-aware matching used by multi-maid @name prefixes. */
class MaidConversationTargetResolverTest {
    @Test
    void matchesFullNamesAtTokenBoundaries() {
        assertTrue(MaidConversationTargetResolver.hasSelectorPrefix("maid alice chop trees", "maid alice"));
        assertTrue(MaidConversationTargetResolver.hasSelectorPrefix("maid alice", "maid alice"));
        assertFalse(MaidConversationTargetResolver.hasSelectorPrefix("maid alice2 chop trees", "maid alice"));
        assertFalse(MaidConversationTargetResolver.hasSelectorPrefix("maid bob chop trees", "maid alice"));
    }
}
