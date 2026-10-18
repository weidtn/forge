package forge.ai.cedh;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import forge.ai.AiCardMemory;
import forge.ai.AiCardMemory.MemorySet;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.card.mana.ManaCost;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.cost.CostTap;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;

public final class ComboPlanner {
    /** Backstop against a line that loops without progress: planner actions allowed per turn. */
    static final int MAX_ACTIONS_PER_TURN = 300;

    private final Player ai;
    private int actionTurn = -1;
    private int actionsThisTurn;

    public ComboPlanner(Player ai) {
        this.ai = ai;
    }

    static Card findByName(CardCollectionView cards, String name) {
        for (Card c : cards) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    /** A spell of this card we can cast and pay for right now announcing {@code x}, or null. */
    private SpellAbility castableSpell(Card c, int x) {
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(new CardCollection(c), ai)) {
            if (!sa.isSpell()) {
                continue;
            }
            if (x > 0) {
                sa.setXManaCostPaid(x);
            }
            if (sa.canPlay() && ComputerUtilMana.canPayManaCost(sa, ai, 0, false)) {
                return sa;
            }
        }
        return null;
    }

    /** The non-mana ability of our permanent {@code c} with this api, targeted as the step says, if playable now. */
    private SpellAbility activatableAbility(Card c, ComboStep.Activate step) {
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(new CardCollection(c), ai)) {
            if (sa.isSpell() || sa.isManaAbility() || sa.getApi() != step.api()) {
                continue;
            }
            if (step.target() != null) {
                GameEntity target = step.target().pick(ai);
                sa.resetTargets();
                if (target == null || !sa.canTarget(target)) {
                    continue;
                }
                sa.getTargets().add(target);
            }
            if (sa.canPlay() && ComputerUtilCost.canPayCost(sa, ai, false)) {
                return sa;
            }
        }
        return null;
    }

    /** The mana ability of {@code c} whose only cost is {T} (Sol Ring, Mana Crypt, a dork), or null. */
    static SpellAbility tapManaAbility(Card c, Player activator) {
        for (SpellAbility sa : c.getManaAbilities()) {
            if (sa.getPayCosts().hasOnlySpecificCostType(CostTap.class)) {
                sa.setActivatingPlayer(activator);
                return sa;
            }
        }
        return null;
    }

    /** The {T} mana ability of an untapped permanent of ours matching the step's source, if playable now. */
    private SpellAbility manaAbility(ComboStep.TapForMana step) {
        for (Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (c.isTapped() || !step.source().test(c)) {
                continue;
            }
            SpellAbility sa = tapManaAbility(c, ai);
            if (sa != null && sa.canPlay() && ComputerUtilCost.canPayCost(sa, ai, false)) {
                return sa;
            }
        }
        return null;
    }

    /** The step's ability on {@code c} (in any zone), unprepared except for X; used for pricing. Null if none. */
    private SpellAbility pricedAbility(ComboStep step, Card c) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (step instanceof ComboStep.Cast cast && sa.isSpell()) {
                sa.setActivatingPlayer(ai);
                if (cast.x() > 0) {
                    sa.setXManaCostPaid(cast.x());
                }
                return sa;
            }
            if (step instanceof ComboStep.Activate activate && !sa.isSpell() && !sa.isManaAbility()
                    && sa.getApi() == activate.api()) {
                sa.setActivatingPlayer(ai);
                return sa;
            }
        }
        return null;
    }

    /** Our copy of a line card: on the battlefield, else in hand; null if neither. */
    private Card ourCard(String name) {
        Card c = findByName(ai.getCardsIn(ZoneType.Battlefield), name);
        return c != null ? c : findByName(ai.getCardsIn(ZoneType.Hand), name);
    }

    /**
     * Abilities of the line's setup steps whose guard holds now, plus the cast of {@code fetched} (a piece about
     * to be tutored to hand) if the line casts it. Null if one of them can't be priced.
     */
    private List<SpellAbility> setupAbilities(ComboLine line, Card fetched) {
        List<SpellAbility> result = new ArrayList<>();
        for (ComboStep step : line.steps()) {
            if (!step.setup()) {
                continue; // TapForMana is never a setup step, so only Cast and Activate get past here
            }
            boolean isCast = step instanceof ComboStep.Cast;
            String name = isCast ? ((ComboStep.Cast) step).card() : ((ComboStep.Activate) step).card();
            Card c;
            if (fetched != null && isCast && name.equals(fetched.getName())) {
                c = fetched;
            } else if (step.when().test(ai)) {
                c = ourCard(name);
            } else {
                continue;
            }
            SpellAbility sa = c == null ? null : pricedAbility(step, c);
            if (sa == null) {
                return null;
            }
            result.add(sa);
        }
        return result;
    }

    private ManaCost totalCost(List<SpellAbility> spells) {
        ManaCost total = ManaCost.ZERO;
        for (SpellAbility sa : spells) {
            ManaCostBeingPaid cost = ComputerUtilMana.calculateManaCost(sa.getPayCosts(), sa, ai, true, 0, false);
            total = ManaCost.combine(total, cost.toManaCost());
        }
        return total;
    }

    /** True if our current sources can pay for all these spells together (colours included). Taps nothing. */
    boolean canPayCombined(List<SpellAbility> spells) {
        if (spells.isEmpty()) {
            return true;
        }
        ManaCost total = totalCost(spells);
        if (ComputerUtilMana.getAvailableManaEstimate(ai, false) < total.getCMC()) {
            return false; // cheap early-out before the shard-by-shard check
        }
        return ComputerUtilMana.canPayManaCost(new ManaCostBeingPaid(total), spells.get(0), ai, false);
    }

    /**
     * Reserves the sources that pay for {@code later} so paying for {@code now} can't spend them (e.g. Demonic Tutor
     * must not tap an Island that Oracle needs). The reservation lasts until the AI next chooses a spell.
     * Returns false and reserves nothing if {@code now} can't be paid from what's left.
     */
    private boolean holdManaFor(List<SpellAbility> later, SpellAbility now) {
        if (later.isEmpty()) {
            return ComputerUtilMana.canPayManaCost(now, ai, 0, false);
        }
        CardCollection held = ComputerUtilMana.getManaSourcesToPayCost(
                new ManaCostBeingPaid(totalCost(later)), later.get(0), ai, false);
        if (held == null) {
            return false;
        }
        for (Card c : held) {
            AiCardMemory.rememberCard(ai, c, MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
        }
        if (ComputerUtilMana.canPayManaCost(now, ai, 0, false)) {
            return true;
        }
        for (Card c : held) {
            AiCardMemory.forgetCard(ai, c, MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
        }
        return false;
    }

    /** All pieces in their zones, extra condition holds, and the line's pending setup is payable with our sources. */
    boolean isExecutable(ComboLine line) {
        for (ComboPiece piece : line.pieces()) {
            if (piece.find(ai) == null) {
                return false;
            }
        }
        if (!line.condition().test(ai)) {
            return false;
        }
        List<SpellAbility> setup = setupAbilities(line, null);
        return setup != null && canPayCombined(setup);
    }

    public ComboLine findExecutableLine() {
        for (ComboLine line : ComboLibrary.ALL) {
            if (isExecutable(line)) {
                return line;
            }
        }
        return null;
    }

    private List<String> missingFrom(ComboLine line) {
        List<String> missing = new ArrayList<>();
        for (ComboPiece piece : line.pieces()) {
            if (piece.find(ai) == null) {
                missing.add(piece.card());
            }
        }
        return missing;
    }

    /**
     * First line (in {@link ComboLibrary#ALL} order) that lacks exactly one piece, provided that piece is in our
     * library and the line's condition holds; else null.
     */
    private ComboLine lineMissingOnePiece() {
        CardCollectionView lib = ai.getCardsIn(ZoneType.Library);
        for (ComboLine line : ComboLibrary.ALL) {
            List<String> missing = missingFrom(line);
            if (missing.size() == 1 && findByName(lib, missing.get(0)) != null && line.condition().test(ai)) {
                return line;
            }
        }
        return null;
    }

    /** Name of the single library card that would complete a line (see {@link #lineMissingOnePiece()}), or null. */
    public String missingPiece() {
        ComboLine line = lineMissingOnePiece();
        return line == null ? null : missingFrom(line).get(0);
    }

    /** The missing piece among a tutor's search options, or null. */
    public Card tutorTarget(CardCollectionView options) {
        String missing = missingPiece();
        return missing == null ? null : findByName(options, missing);
    }

    /**
     * A tutor from hand that can fetch the missing piece. To-hand tutors only if the tutor plus the whole line is
     * payable this turn; to-top tutors only need their own cost, and are skipped if the piece is already on top.
     */
    private SpellAbility tutorStep() {
        ComboLine line = lineMissingOnePiece();
        if (line == null) {
            return null;
        }
        CardCollectionView hand = ai.getCardsIn(ZoneType.Hand);
        CardCollectionView lib = ai.getCardsIn(ZoneType.Library);
        Card target = findByName(lib, missingFrom(line).get(0));
        boolean alreadyOnTop = lib.get(0) == target;
        SpellAbility toTop = null;
        for (Card c : hand) {
            Predicate<Card> canFind = ComboLibrary.TUTORS.get(c.getName());
            if (canFind == null || !canFind.test(target)) {
                continue;
            }
            SpellAbility sa = castableSpell(c, 0);
            if (sa == null) {
                continue;
            }
            if (ZoneType.Hand.name().equals(sa.getParam("Destination"))) {
                List<SpellAbility> lineSpells = setupAbilities(line, target);
                if (lineSpells == null) {
                    return null;
                }
                List<SpellAbility> all = new ArrayList<>();
                all.add(sa);
                all.addAll(lineSpells);
                if (canPayCombined(all) && holdManaFor(lineSpells, sa)) {
                    return sa;
                }
            } else if (toTop == null && !alreadyOnTop) {
                toTop = sa;
            }
        }
        return toTop;
    }

    /**
     * The line's first step whose guard holds, prepared (X, targets) if it is playable now; else null (wait).
     * A {@link ComboStep.TapForMana} with nothing left to tap is passed over: the next step may be paid otherwise.
     */
    private SpellAbility firstStep(ComboLine line) {
        for (ComboStep step : line.steps()) {
            if (!step.when().test(ai)) {
                continue;
            }
            if (step instanceof ComboStep.TapForMana tap) {
                SpellAbility mana = manaAbility(tap);
                if (mana != null) {
                    return mana;
                }
                continue;
            }
            if (step instanceof ComboStep.Cast cast) {
                Card c = findByName(ai.getCardsIn(ZoneType.Hand), cast.card());
                return c == null ? null : castableSpell(c, cast.x());
            }
            ComboStep.Activate activate = (ComboStep.Activate) step;
            Card c = findByName(ai.getCardsIn(ZoneType.Battlefield), activate.card());
            return c == null ? null : activatableAbility(c, activate);
        }
        return null;
    }

    /**
     * Next spell or ability to play: the first step of an executable line, else a tutor for the missing piece, or
     * null. Only acts on an empty stack, and at most {@link #MAX_ACTIONS_PER_TURN} times per turn.
     */
    public SpellAbility nextStep() {
        if (!ai.getGame().getStack().isEmpty()) {
            return null;
        }
        int turn = ai.getGame().getPhaseHandler().getTurn();
        if (turn != actionTurn) {
            actionTurn = turn;
            actionsThisTurn = 0;
        }
        if (actionsThisTurn >= MAX_ACTIONS_PER_TURN) {
            return null;
        }
        ComboLine line = findExecutableLine();
        SpellAbility sa = line == null ? tutorStep() : firstStep(line);
        if (sa != null) {
            actionsThisTurn++;
        }
        return sa;
    }

    /** Planned target for one of our triggers (e.g. Heliod's counter onto Ballista), or null to let the AI choose. */
    public GameEntity triggerTarget(SpellAbility trigger) {
        SpellAbility sa = trigger instanceof WrappedAbility wrapped ? wrapped.getWrappedAbility() : trigger;
        if (sa.getHostCard() == null || !sa.usesTargeting()) {
            return null;
        }
        for (ComboLine line : ComboLibrary.ALL) {
            ComboTarget planned = line.triggerTargets().get(sa.getHostCard().getName());
            GameEntity target = planned == null ? null : planned.pick(ai);
            if (target != null && sa.canTarget(target)) {
                return target;
            }
        }
        return null;
    }

    /** The card one of our permanents should exile from {@code options} (Scepter imprints Reversal), or null. */
    public Card exileChoice(SpellAbility sa, CardCollectionView options) {
        Card host = sa.getHostCard();
        if (host == null) {
            return null;
        }
        for (ComboLine line : ComboLibrary.ALL) {
            String wanted = line.exileChoices().get(host.getName());
            Card c = wanted == null ? null : findByName(options, wanted);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    /** A legal name guaranteed NOT to be in our library: prefer the win card if it's in hand. */
    public String consultationName() {
        CardCollectionView lib = ai.getCardsIn(ZoneType.Library);
        ComboLine line = findExecutableLine();
        if (line != null && findByName(lib, line.winCard()) == null) {
            return line.winCard();
        }
        for (Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (findByName(lib, c.getName()) == null) {
                return c.getName();
            }
        }
        return null;
    }

    /** True while the Pact line is live: Oracle in hand, so we decline every card. */
    public boolean wantsToExileLibraryWithPact() {
        return findByName(ai.getCardsIn(ZoneType.Hand), ComboLibrary.ORACLE) != null;
    }

    /** Opening-hand check: keep iff the hand has a combo piece or tutor, and at least two mana sources. */
    public boolean keepHand() {
        boolean hasPiece = false;
        int sources = 0;
        for (Card c : ai.getCardsIn(ZoneType.Hand)) {
            String name = c.getName();
            if (ComboLibrary.TUTORS.containsKey(name) || ComboLibrary.ALL.stream().anyMatch(l -> l.pieces().stream().anyMatch(pc -> pc.card().equals(name)))) {
                hasPiece = true;
            }
            if (c.isLand() || !c.getManaAbilities().isEmpty()) {
                sources++;
            }
        }
        return hasPiece && sources >= 2;
    }
}
