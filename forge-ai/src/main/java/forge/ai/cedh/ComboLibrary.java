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
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public final class ComboLibrary {
    private ComboLibrary() {}

    public static final String ORACLE = "Thassa's Oracle";
    public static final String CONSULTATION = "Demonic Consultation";
    public static final String TAINTED_PACT = "Tainted Pact";
    public static final String HELIOD = "Heliod, Sun-Crowned";
    public static final String BALLISTA = "Walking Ballista";
    public static final String SCEPTER = "Isochron Scepter";
    public static final String REVERSAL = "Dramatic Reversal";
    public static final String AETHERFLUX = "Aetherflux Reservoir";
    public static final String BREACH = "Underworld Breach";
    public static final String LED = "Lion's Eye Diamond";
    public static final String BRAIN_FREEZE = "Brain Freeze";

    /** Scepter's activation is {2}, {T}. */
    private static final int SCEPTER_ACTIVATION_MANA = 2;
    /** Aetherflux's shot costs 50 life; paying it at exactly 50 would kill us. */
    private static final int AETHERFLUX_LIFE = 50;

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
                        new ComboStep.Cast(BALLISTA, 2, null, p -> ballistaInHand.find(p) != null, true),
                        new ComboStep.Activate(BALLISTA, ApiType.PutCounter, null, p -> ballistaCounters(p) == 1, true),
                        new ComboStep.Activate(HELIOD, ApiType.Pump, ComboTarget.ourCard(BALLISTA),
                                p -> !ballistaHasLifelink(p), true),
                        new ComboStep.Activate(BALLISTA, ApiType.DealDamage, ComboTarget.weakestOpponent(),
                                p -> ballistaCounters(p) >= 2 && ballistaHasLifelink(p), false)),
                Map.of(HELIOD, ComboTarget.ourCard(BALLISTA)), Map.of());
    }

    /**
     * Mana our nonland permanents make per untap: what Dramatic Reversal gives back. Lands aren't untapped by it, and
     * summoning-sick dorks can't tap again, so neither counts.
     */
    static int nonlandManaPerUntap(Player p) {
        int total = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand() || c.isSick()) {
                continue;
            }
            SpellAbility mana = ComboPlanner.tapManaAbility(c, p);
            total += mana == null ? 0 : mana.amountOfManaGenerated(true);
        }
        return total;
    }

    /** Our Scepter on the battlefield with Dramatic Reversal imprinted, or null. */
    private static Card scepterWithReversal(Player p) {
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(SCEPTER) && c.getImprintedCards().anyMatch(i -> i.getName().equals(REVERSAL))) {
                return c;
            }
        }
        return null;
    }

    static boolean scepterHasReversal(Player p) {
        return scepterWithReversal(p) != null;
    }

    /**
     * Isochron Scepter + Dramatic Reversal, Aetherflux Reservoir as the outlet: each Scepter activation casts a
     * Reversal copy (Aetherflux gains life for it) and untaps the rocks that pay the next activation. The shot comes
     * first, so the loop stops the moment life passes 50. Reversal in hand only works if Scepter is too (to imprint it).
     */
    private static ComboLine scepterReversal() {
        ComboPiece scepterInHand = ComboPiece.inHand(SCEPTER);
        Predicate<Player> scepterLive = p -> scepterHasReversal(p) || scepterInHand.find(p) != null;
        return new ComboLine("scepter-reversal-aetherflux",
                List.of(ComboPiece.inHandOrPlay(SCEPTER), ComboPiece.imprintable(REVERSAL),
                        ComboPiece.onBattlefield(AETHERFLUX)),
                p -> scepterLive.test(p) && nonlandManaPerUntap(p) >= SCEPTER_ACTIVATION_MANA,
                List.of(ComboStep.Cast.fromHand(SCEPTER),
                        new ComboStep.Activate(AETHERFLUX, ApiType.DealDamage, ComboTarget.weakestOpponent(),
                                p -> p.getLife() > AETHERFLUX_LIFE, false),
                        new ComboStep.TapForMana(c -> !c.isLand(), p -> {
                            Card scepter = scepterWithReversal(p);
                            return scepter != null && scepter.isUntapped()
                                    && p.getManaPool().totalMana() < SCEPTER_ACTIVATION_MANA;
                        }),
                        new ComboStep.Activate(SCEPTER, ApiType.Play, null, scepterLive, true)),
                Map.of(), Map.of(SCEPTER, REVERSAL));
    }

    /**
     * Underworld Breach + Lion's Eye Diamond + Brain Freeze: LED's mana pays for escaping Brain Freeze, whose storm
     * copies mill opponents (or us, for escape fuel), and LED escapes again. {@link BreachLoop} holds the arithmetic:
     * the line is live only while its projection reaches the kill, Oracle's (self-mill to its devotion) if Oracle is
     * in hand or graveyard, else milling every opponent out. Breach in hand is cast first.
     */
    private static ComboLine breachLedBrainFreeze() {
        return new ComboLine("breach-led-brainfreeze",
                List.of(ComboPiece.inHandOrPlay(BREACH), ComboPiece.inHandPlayOrGraveyard(LED),
                        ComboPiece.inHandPlayOrGraveyard(BRAIN_FREEZE)),
                p -> BreachLoop.of(p).live(),
                List.of(ComboStep.Cast.fromHand(BREACH),
                        new ComboStep.Cast(ORACLE, 0, null, p -> BreachLoop.of(p).oracleReady(), false),
                        new ComboStep.Cast(BRAIN_FREEZE, 0, BreachLoop::target,
                                p -> BreachLoop.of(p).canCastBrainFreeze(), false),
                        new ComboStep.Cast(LED, 0, null, p -> BreachLoop.of(p).canCastLed(), false),
                        new ComboStep.ManaAbility(LED, "U", p -> BreachLoop.of(p).ledOnBattlefield())),
                Map.of(BRAIN_FREEZE, BreachLoop::target), Map.of());
    }

    public static final List<ComboLine> ALL = List.of(
            ComboLine.castInOrder("thoracle-finish", List.of(ORACLE), p -> libraryAtMost(p, 2)),
            ComboLine.castInOrder("thoracle-consult", List.of(CONSULTATION, ORACLE), p -> true),
            ComboLine.castInOrder("thoracle-pact", List.of(TAINTED_PACT, ORACLE), ComboLibrary::libraryHasNoDuplicateNames),
            heliodBallista(),
            scepterReversal(),
            breachLedBrainFreeze()
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

    /**
     * Cards the normal AI must never cast on its own: they only make sense inside a line. Breach is sacrificed at end
     * of turn, so casting it without the loop throws the piece away.
     */
    public static final Set<String> COMBO_ONLY = Set.of(ORACLE, CONSULTATION, TAINTED_PACT, BREACH);

    public static boolean isComboOnly(Card c) {
        return c != null && COMBO_ONLY.contains(c.getName());
    }
}
