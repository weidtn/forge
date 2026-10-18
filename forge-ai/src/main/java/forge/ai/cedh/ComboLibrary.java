package forge.ai.cedh;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import forge.card.MagicColor;
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
    public static final String KINNAN = "Kinnan, Bonder Prodigy";
    public static final String KNACKSAW = "Knacksaw Clique";
    public static final String VITALITY = "Enduring Vitality";
    public static final String SHANG_CHI = "Shang-Chi, Master of Kung Fu";
    public static final String DEADEYE = "Deadeye Navigator";
    public static final String GREAT_OAK = "Great Oak Guardian";
    public static final String ENTOMB = "Entomb";
    public static final String SHALLOW_GRAVE = "Shallow Grave";
    public static final String REANIMATE = "Reanimate";
    public static final String UNEARTH = "Unearth";
    public static final String PERSIST = "Persist";
    public static final String NECROMANCY = "Necromancy";
    public static final String CORPSE_DANCE = "Corpse Dance";
    public static final String INALLA = "Inalla, Archmage Ritualist";
    public static final String SCHOLAR = "Scholar of the Ages";
    public static final String BURNT_OFFERING = "Burnt Offering";
    public static final String SACRIFICE = "Sacrifice";
    public static final String CULLING = "Culling the Weak";

    /** Spells of the Oracle-reanimate lines, cheapest first (Unearth before Reanimate: same mana, no life). */
    static final List<String> REANIMATORS = List.of(UNEARTH, REANIMATE, SHALLOW_GRAVE, PERSIST, NECROMANCY, CORPSE_DANCE);

    /** Scepter's activation is {2}, {T}. */
    private static final int SCEPTER_ACTIVATION_MANA = 2;
    /** Aetherflux's shot costs 50 life; paying it at exactly 50 would kill us. */
    private static final int AETHERFLUX_LIFE = 50;
    /** Kinnan's dig costs {5}{G}{U}. */
    private static final int KINNAN_ACTIVATION_MANA = 7;
    /** Deadeye's flicker costs {1}{U}; the Great Oak loop runs only if an untap makes at least two more than that. */
    private static final int GREAT_OAK_MANA_PER_UNTAP = 4;
    /** The Knacksaw line's kill pieces; Clique from hand needs Shang-Chi, since it enters summoning-sick. */
    private static final ComboPiece CLIQUE_PIECE = ComboPiece.inHandOrPlay(KNACKSAW);
    private static final ComboPiece VITALITY_PIECE = ComboPiece.inHandOrPlay(VITALITY);
    /** Reanimate costs life equal to Oracle's mana value. */
    private static final int ORACLE_MANA_VALUE = 2;

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
                Map.of(HELIOD, ComboTarget.ourCard(BALLISTA)), Map.of(), Map.of(), ComboChoices.NONE);
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
                        new ComboStep.TapForMana(c -> !c.isLand(), null, p -> {
                            Card scepter = scepterWithReversal(p);
                            return scepter != null && scepter.isUntapped()
                                    && p.getManaPool().totalMana() < SCEPTER_ACTIVATION_MANA;
                        }),
                        new ComboStep.Activate(SCEPTER, ApiType.Play, null, scepterLive, true)),
                Map.of(), Map.of(SCEPTER, REVERSAL), Map.of(), ComboChoices.NONE);
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
                Map.of(BRAIN_FREEZE, BreachLoop::target), Map.of(), Map.of(), ComboChoices.NONE);
    }

    private static Card ourPermanent(Player p, String name) {
        return ComboPlanner.findByName(p.getCardsIn(ZoneType.Battlefield), name);
    }

    private static boolean poolHas(Player p, byte color) {
        return p.getManaPool().getAmountOfColor(color) > 0;
    }

    /** The opponent who draws next still has cards to exile (see {@link ComboTarget#nextOpponentToDraw()}). */
    static boolean nextDrawerHasLibrary(Player p) {
        return ComboTarget.nextOpponentToDraw().pick(p) != null;
    }

    /**
     * Knacksaw Clique can tap and untap this turn: on the battlefield and not summoning-sick (Shang-Chi counts), or
     * in hand with Shang-Chi out, since it enters summoning-sick.
     */
    static boolean cliqueCanLoop(Player p) {
        Card clique = ourPermanent(p, KNACKSAW);
        return clique != null ? !clique.isAbilitySick() : ourPermanent(p, SHANG_CHI) != null;
    }

    private static boolean cliqueTapped(Player p) {
        Card clique = ourPermanent(p, KNACKSAW);
        return clique != null && clique.isTapped();
    }

    private static boolean cliqueUntapped(Player p) {
        Card clique = ourPermanent(p, KNACKSAW);
        return clique != null && clique.isUntapped();
    }

    /**
     * Knacksaw Clique once the kill is on the battlefield and an opponent still has cards: the loop goes on in later
     * opponents' upkeeps, where Clique starts it only if it is untapped (or other mana pays the first {1}{U}). The AI
     * neither taps it for mana nor attacks with it meanwhile.
     */
    static boolean keepUntapped(Player p, Card c) {
        return c.getName().equals(KNACKSAW) && c.getController() == p && c.isInPlay()
                && ourPermanent(p, VITALITY) != null && ourPermanent(p, KINNAN) != null
                && p.getOpponents().anyMatch(o -> !o.getCardsIn(ZoneType.Library).isEmpty());
    }

    /**
     * Kinnan + Knacksaw Clique + Enduring Vitality: Clique taps for U through Vitality, Kinnan adds another U, and
     * the {1}{U} activation untaps Clique while the opponent who draws next exiles their top card. Mana-neutral. The
     * line stops once that library is empty (they lose on the draw) and goes on in the next opponent's upkeep, before
     * their draw: one library per turn. Each exiled card leaves an until-end-of-turn may-play effect, and every
     * state check and every AI's priority pays for all of them, so emptying all libraries in one turn took Forge
     * minutes in four-player; the opponents still all lose before our next turn.
     */
    private static ComboLine knacksawVitality() {
        return new ComboLine("kinnan-knacksaw-vitality",
                List.of(CLIQUE_PIECE, VITALITY_PIECE, ComboPiece.onBattlefield(KINNAN)),
                p -> cliqueCanLoop(p) && nextDrawerHasLibrary(p),
                List.of(ComboStep.Cast.fromHand(VITALITY),
                        ComboStep.Cast.fromHand(KNACKSAW),
                        new ComboStep.Activate(KNACKSAW, ApiType.Dig, ComboTarget.nextOpponentToDraw(),
                                ComboLibrary::cliqueTapped, false),
                        new ComboStep.ManaAbility(KNACKSAW, "U", ComboLibrary::cliqueUntapped)),
                Map.of(), Map.of(), Map.of(), ComboChoices.NONE);
    }

    /**
     * Kinnan's dig has a purpose: a Knacksaw line piece is neither in hand nor on the battlefield, and every such
     * piece is still in our library. The engines below make mana only while this holds.
     */
    static boolean killPieceInLibrary(Player p) {
        boolean missing = false;
        for (ComboPiece piece : List.of(CLIQUE_PIECE, VITALITY_PIECE)) {
            if (piece.find(p) != null) {
                continue;
            }
            if (ComboPlanner.findByName(p.getCardsIn(ZoneType.Library), piece.card()) == null) {
                return false;
            }
            missing = true;
        }
        return missing;
    }

    /** The pool alone pays Kinnan's {5}{G}{U}, so the engine's floating mana is spent, not our lands. */
    static boolean kinnanPaidFromPool(Player p) {
        return p.getManaPool().totalMana() >= KINNAN_ACTIVATION_MANA
                && poolHas(p, MagicColor.GREEN) && poolHas(p, MagicColor.BLUE);
    }

    private static boolean pairedWithDeadeye(Player p, String name) {
        Card c = ourPermanent(p, name);
        return c != null && c.isPaired() && DEADEYE.equals(c.getPairedWith().getName());
    }

    /**
     * Kinnan + Deadeye Navigator + Shang-Chi: Shang-Chi taps for two mana (creature abilities only) and Kinnan adds
     * one more; two pay Deadeye's {1}{U} flicker, which brings Shang-Chi back untapped, and Deadeye's soulbond pairs
     * them again. Net +1 per loop, spent on Kinnan's dig for the missing Knacksaw piece (Kinnan's ability is a
     * creature's, so the restricted mana pays it). The flicker comes first, so the dig never spends the mana that
     * restarts the loop. Shang-Chi makes G only when the pool has U for the flicker but no G.
     */
    private static ComboLine deadeyeShangChi() {
        Predicate<Player> untapped = p -> {
            Card shangChi = ourPermanent(p, SHANG_CHI);
            return shangChi != null && shangChi.isUntapped();
        };
        return new ComboLine("kinnan-deadeye-shangchi",
                List.of(ComboPiece.onBattlefield(DEADEYE), ComboPiece.onBattlefield(SHANG_CHI),
                        ComboPiece.onBattlefield(KINNAN)),
                p -> killPieceInLibrary(p) && nextDrawerHasLibrary(p) && pairedWithDeadeye(p, SHANG_CHI),
                List.of(new ComboStep.Activate(SHANG_CHI, ApiType.ChangeZone, null, untapped.negate(), false),
                        new ComboStep.Activate(KINNAN, ApiType.Dig, null, ComboLibrary::kinnanPaidFromPool, false),
                        new ComboStep.ManaAbility(SHANG_CHI, "G",
                                p -> untapped.test(p) && poolHas(p, MagicColor.BLUE) && !poolHas(p, MagicColor.GREEN)),
                        new ComboStep.ManaAbility(SHANG_CHI, "U", untapped)),
                Map.of(), Map.of(), Map.of(DEADEYE, SHANG_CHI), ComboChoices.NONE);
    }

    /** The {T} mana ability of our creature {@code c} if it can tap now (summoning sickness and Shang-Chi checked), else null. */
    private static SpellAbility creatureMana(Card c, Player p) {
        return c.isCreature() && !c.isAbilitySick() ? ComboPlanner.tapManaAbility(c, p) : null;
    }

    /**
     * Mana our creatures make per Great Oak untap, Kinnan's extra mana per source included. Great Oak itself comes
     * back summoning-sick from each flicker, so it doesn't count.
     */
    static int creatureManaPerUntap(Player p) {
        int perSource = ourPermanent(p, KINNAN) != null ? 1 : 0;
        int total = 0;
        for (Card c : p.getCreaturesInPlay()) {
            SpellAbility mana = c.getName().equals(GREAT_OAK) ? null : creatureMana(c, p);
            total += mana == null ? 0 : mana.amountOfManaGenerated(true) + perSource;
        }
        return total;
    }

    private static boolean makesBlue(Card c) {
        SpellAbility mana = creatureMana(c, c.getController());
        return mana != null && mana.canProduce("U");
    }

    /** Every creature of ours with a {T} mana ability is untapped: Great Oak's trigger just resolved. */
    private static boolean creaturesUntapped(Player p) {
        return !p.getCreaturesInPlay().anyMatch(c -> c.isTapped() && ComboPlanner.tapManaAbility(c, p) != null);
    }

    /**
     * Kinnan + Deadeye Navigator + Great Oak Guardian: tap every creature for mana, flicker Great Oak ({1}{U}), its
     * enter trigger (aimed at us) untaps our creatures, and Deadeye's soulbond pairs them again. Needs creatures making
     * {1}{U} plus at least two more per untap; the surplus pays Kinnan's dig for the missing Knacksaw piece. The dig
     * happens only right after an untap, so the creatures still make the next flicker's mana.
     */
    private static ComboLine deadeyeGreatOak() {
        return new ComboLine("kinnan-deadeye-greatoak",
                List.of(ComboPiece.onBattlefield(DEADEYE), ComboPiece.onBattlefield(GREAT_OAK),
                        ComboPiece.onBattlefield(KINNAN)),
                p -> killPieceInLibrary(p) && nextDrawerHasLibrary(p) && pairedWithDeadeye(p, GREAT_OAK)
                        && creatureManaPerUntap(p) >= GREAT_OAK_MANA_PER_UNTAP
                        && p.getCreaturesInPlay().anyMatch(ComboLibrary::makesBlue),
                List.of(new ComboStep.Activate(KINNAN, ApiType.Dig, null,
                                p -> kinnanPaidFromPool(p) && creaturesUntapped(p), false),
                        new ComboStep.TapForMana(c -> c.isCreature() && makesBlue(c), "U",
                                p -> p.getManaPool().getAmountOfColor(MagicColor.BLUE) < 2),   // flicker and dig
                        new ComboStep.TapForMana(Card::isCreature, "G", p -> true),
                        new ComboStep.Activate(GREAT_OAK, ApiType.ChangeZone, null, p -> true, false)),
                Map.of(GREAT_OAK, ComboTarget.self()), Map.of(), Map.of(DEADEYE, GREAT_OAK), ComboChoices.NONE);
    }

    private static boolean inZone(Player p, ZoneType zone, String name) {
        return ComboPlanner.findByName(p.getCardsIn(zone), name) != null;
    }

    /** The top creature card of our graveyard (the last one put there), or null: what Shallow Grave returns. */
    static Card topGraveyardCreature(Player p) {
        CardCollectionView graveyard = p.getCardsIn(ZoneType.Graveyard);
        for (int i = graveyard.size() - 1; i >= 0; i--) {
            if (graveyard.get(i).isCreature()) {
                return graveyard.get(i);
            }
        }
        return null;
    }

    /** Consultation in hand, else Tainted Pact with a library it can exile whole (declining every card). */
    static boolean canExileLibrary(Player p) {
        return inZone(p, ZoneType.Hand, CONSULTATION)
                || (inZone(p, ZoneType.Hand, TAINTED_PACT) && libraryHasNoDuplicateNames(p));
    }

    /**
     * Thassa's Oracle reanimated onto an empty library by {@code r}: Entomb puts Oracle into our graveyard if it is
     * still in the library (see {@link ComboPlanner#zoneChoice}), Consultation naming a card not in the library
     * (else Tainted Pact) exiles the library, then {@code r} returns Oracle. Each pending cast is guarded by its card
     * being in hand, so the affordability check prices the rest of the line: it never starts unless {@code r} is
     * still payable after the exile, which would otherwise deck us. Shallow Grave and Corpse Dance take the top
     * creature card of our graveyard, which Oracle is right after Entomb; Necromancy's enter trigger takes the target.
     */
    private static ComboLine oracleReanimate(String r) {
        boolean topCreatureOnly = r.equals(SHALLOW_GRAVE) || r.equals(CORPSE_DANCE);
        boolean targetsOnCast = !topCreatureOnly && !r.equals(NECROMANCY);
        Predicate<Player> libraryLeft = p -> !p.getCardsIn(ZoneType.Library).isEmpty();
        Predicate<Player> oracleReachable = p -> {
            if (inZone(p, ZoneType.Graveyard, ORACLE)) {
                Card top = topGraveyardCreature(p);
                return (!topCreatureOnly || ORACLE.equals(top.getName()))
                        && (!libraryLeft.test(p) || canExileLibrary(p));
            }
            return inZone(p, ZoneType.Library, ORACLE) && inZone(p, ZoneType.Hand, ENTOMB) && canExileLibrary(p);
        };
        Predicate<Player> survives = r.equals(REANIMATE) ? p -> p.getLife() > ORACLE_MANA_VALUE : p -> true;
        ComboTarget oracle = ComboTarget.ourGraveyardCard(ORACLE);
        return new ComboLine("oracle-reanimate-" + r.toLowerCase().replace(' ', '-'),
                List.of(ComboPiece.inHand(r), ComboPiece.inLibraryOrGraveyard(ORACLE)),
                oracleReachable.and(survives),
                List.of(new ComboStep.Cast(ENTOMB, 0, null,
                                p -> inZone(p, ZoneType.Hand, ENTOMB) && inZone(p, ZoneType.Library, ORACLE), true),
                        new ComboStep.Cast(CONSULTATION, 0, null,
                                p -> inZone(p, ZoneType.Hand, CONSULTATION) && libraryLeft.test(p), true),
                        new ComboStep.Cast(TAINTED_PACT, 0, null, p -> inZone(p, ZoneType.Hand, TAINTED_PACT)
                                && !inZone(p, ZoneType.Hand, CONSULTATION) && libraryLeft.test(p), true),
                        new ComboStep.Cast(r, 0, targetsOnCast ? oracle : null, p -> inZone(p, ZoneType.Hand, r), true)),
                r.equals(NECROMANCY) ? Map.of(NECROMANCY, oracle) : Map.of(), Map.of(), Map.of(), ComboChoices.NONE);
    }

    /**
     * Inalla + Scholar of the Ages + Shallow Grave + an outlet (Burnt Offering, Sacrifice or Culling the Weak) +
     * Entomb: the outlet sacrifices Scholar for mana, Shallow Grave returns it, and its enter trigger (plus the token
     * copy's, if Inalla's {1} is paid) returns Shallow Grave, the outlet, Entomb and Consultation. Entomb finds Scholar,
     * then Consultation, then Oracle; Consultation exiles the library and Shallow Grave returns Oracle.
     * {@link ScholarLoop} holds the projection and the resolution-time choices; the Oracle-reanimate lines usually
     * take over for the last three casts.
     */
    private static ComboLine scholarEntomb() {
        List<ComboStep> steps = new ArrayList<>(List.of(
                new ComboStep.Cast(SHALLOW_GRAVE, 0, null, p -> ScholarLoop.of(p).casts(SHALLOW_GRAVE), false),
                new ComboStep.Cast(CONSULTATION, 0, null, p -> ScholarLoop.of(p).casts(CONSULTATION), false),
                new ComboStep.Cast(ENTOMB, 0, null, p -> ScholarLoop.of(p).casts(ENTOMB), false)));
        for (String outlet : ScholarLoop.OUTLETS) {
            steps.add(new ComboStep.Cast(outlet, 0, null, p -> ScholarLoop.of(p).casts(outlet), false));
        }
        return new ComboLine("scholar-entomb",
                List.of(ComboPiece.inCommandOrPlay(INALLA), ComboPiece.inLibraryOrGraveyard(ORACLE)),
                p -> ScholarLoop.of(p).finishes(),
                steps, Map.of(), Map.of(), Map.of(), ScholarLoop.CHOICES);
    }

    public static final List<ComboLine> ALL = allLines();

    /** Thoracle lines first, then the Oracle-reanimate lines, then the rest. */
    private static List<ComboLine> allLines() {
        List<ComboLine> lines = new ArrayList<>(List.of(
                ComboLine.castInOrder("thoracle-finish", List.of(ORACLE), p -> libraryAtMost(p, 2)),
                ComboLine.castInOrder("thoracle-consult", List.of(CONSULTATION, ORACLE), p -> true),
                ComboLine.castInOrder("thoracle-pact", List.of(TAINTED_PACT, ORACLE),
                        ComboLibrary::libraryHasNoDuplicateNames)));
        for (String r : REANIMATORS) {
            lines.add(oracleReanimate(r));
        }
        lines.addAll(List.of(
                heliodBallista(),
                scepterReversal(),
                breachLedBrainFreeze(),
                knacksawVitality(),
                deadeyeShangChi(),
                deadeyeGreatOak(),
                scholarEntomb()));
        return List.copyOf(lines);
    }

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
     * of turn, so casting it without the loop throws the piece away; so does Shallow Grave's creature, exiled at end
     * of turn, and Entomb would bury the stock choice instead of Oracle.
     */
    public static final Set<String> COMBO_ONLY = Set.of(ORACLE, CONSULTATION, TAINTED_PACT, BREACH, ENTOMB,
            SHALLOW_GRAVE);

    public static boolean isComboOnly(Card c) {
        return c != null && COMBO_ONLY.contains(c.getName());
    }
}
