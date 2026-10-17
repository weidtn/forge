package forge.ai.cedh;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public final class ComboLibrary {
    private ComboLibrary() {}

    public static final String ORACLE = "Thassa's Oracle";
    public static final String CONSULTATION = "Demonic Consultation";
    public static final String TAINTED_PACT = "Tainted Pact";

    /** Oracle alone has devotion 2, so a library of <= 2 cards is a guaranteed win. */
    static boolean libraryAtMost(Player p, int n) {
        return p.getCardsIn(ZoneType.Library).size() <= n;
    }

    static boolean libraryHasNoDuplicateNames(Player p) {
        Set<String> seen = new HashSet<>();
        CardCollectionView lib = p.getCardsIn(ZoneType.Library);
        for (Card c : lib) {
            if (!seen.add(c.getName())) {
                return false;
            }
        }
        return true;
    }

    public static final List<ComboLine> ALL = List.of(
            new ComboLine("thoracle-finish", List.of(ORACLE), p -> libraryAtMost(p, 2)),
            new ComboLine("thoracle-consult", List.of(CONSULTATION, ORACLE), p -> true),
            new ComboLine("thoracle-pact", List.of(TAINTED_PACT, ORACLE), ComboLibrary::libraryHasNoDuplicateNames)
    );

    /**
     * Tutors and what they can find. Destination (hand vs. top of library) is read from the card script.
     * Demonic Tutor goes to hand; the rest put the card on top.
     */
    public static final Map<String, Predicate<Card>> TUTORS = Map.of(
            "Demonic Tutor", c -> true,
            "Vampiric Tutor", c -> true,
            "Imperial Seal", c -> true,
            "Mystical Tutor", c -> c.isInstant() || c.isSorcery(),
            "Worldly Tutor", Card::isCreature,
            "Personal Tutor", Card::isSorcery
    );

    /** Cards the normal AI must never cast on its own: they only make sense inside a line. */
    public static final Set<String> COMBO_ONLY = Set.of(ORACLE, CONSULTATION, TAINTED_PACT);

    public static boolean isComboOnly(Card c) {
        return c != null && COMBO_ONLY.contains(c.getName());
    }
}
