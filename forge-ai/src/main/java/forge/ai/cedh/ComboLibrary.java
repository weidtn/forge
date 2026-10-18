package forge.ai.cedh;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CounterEnumType;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public final class ComboLibrary {
    private ComboLibrary() {}

    public static final String ORACLE = "Thassa's Oracle";
    public static final String CONSULTATION = "Demonic Consultation";
    public static final String TAINTED_PACT = "Tainted Pact";
    public static final String HELIOD = "Heliod, Sun-Crowned";
    public static final String BALLISTA = "Walking Ballista";

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

    private static Card ballista(Player p) {
        return ComboPlanner.findByName(p.getCardsIn(ZoneType.Battlefield), BALLISTA);
    }

    static int ballistaCounters(Player p) {
        Card b = ballista(p);
        return b == null ? 0 : b.getCounters(CounterEnumType.P1P1);
    }

    /** False while Ballista isn't on the battlefield, so the pump is priced in before Ballista is cast. */
    static boolean ballistaHasLifelink(Player p) {
        Card b = ballista(p);
        return b != null && b.hasKeyword(Keyword.LIFELINK);
    }

    /**
     * Heliod + Ballista: lifelinked Ballista pings, Heliod puts the counter back. Ballista needs 2 counters before
     * its first ping (the last counter would kill it), so a 1-counter Ballista first buys one with {4}.
     */
    private static ComboLine heliodBallista() {
        ComboPiece ballistaInHand = ComboPiece.inHand(BALLISTA);
        return new ComboLine("heliod-ballista",
                List.of(ComboPiece.inHandOrPlay(HELIOD), ComboPiece.inHandOrPlay(BALLISTA)),
                p -> true,
                List.of(ComboStep.Cast.fromHand(HELIOD),
                        new ComboStep.Cast(BALLISTA, 2, p -> ballistaInHand.find(p) != null, true),
                        new ComboStep.Activate(BALLISTA, ApiType.PutCounter, null, p -> ballistaCounters(p) == 1, true),
                        new ComboStep.Activate(HELIOD, ApiType.Pump, ComboTarget.ourCard(BALLISTA),
                                p -> !ballistaHasLifelink(p), true),
                        new ComboStep.Activate(BALLISTA, ApiType.DealDamage, ComboTarget.weakestOpponent(),
                                p -> ballistaCounters(p) >= 2 && ballistaHasLifelink(p), false)),
                Map.of(HELIOD, ComboTarget.ourCard(BALLISTA)));
    }

    public static final List<ComboLine> ALL = List.of(
            ComboLine.castInOrder("thoracle-finish", List.of(ORACLE), p -> libraryAtMost(p, 2)),
            ComboLine.castInOrder("thoracle-consult", List.of(CONSULTATION, ORACLE), p -> true),
            ComboLine.castInOrder("thoracle-pact", List.of(TAINTED_PACT, ORACLE), ComboLibrary::libraryHasNoDuplicateNames),
            heliodBallista()
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
