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
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.cost.CostPayment;
import forge.game.cost.CostTap;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.AlternativeCost;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;

public final class ComboPlanner {
    /**
     * Backstop against a line that loops without progress: planner actions allowed per turn. The Knacksaw loop takes
     * two per exiled card and empties one library per turn (at most ~95 cards in Commander), and each Kinnan dig the
     * engines pay for takes ~15 more.
     */
    static final int MAX_ACTIONS_PER_TURN = 300;

    private final Player ai;
    private int actionTurn = -1;
    private int actionsThisTurn;
    /** The spell or ability {@link #nextStep()} last returned. */
    private SpellAbility lastPlayed;
    /** The line of the step {@link #nextStep()} last returned (null for a tutor); reset each turn. */
    private ComboLine activeLine;

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

    /** Sets the step's target on {@code sa}; false if it has none the ability can take. */
    private boolean setTarget(SpellAbility sa, ComboTarget planned) {
        if (planned == null) {
            return true;
        }
        GameEntity target = planned.pick(ai);
        sa.resetTargets();
        if (target == null || !sa.canTarget(target)) {
            return false;
        }
        sa.getTargets().add(target);
        return true;
    }

    /**
     * A spell of this card we can cast and pay for right now announcing {@code x} and taking {@code target} (null:
     * untargeted), or null. From the graveyard that is an alternative cost such as escape, non-mana parts included.
     */
    private SpellAbility castableSpell(Card c, int x, ComboTarget target) {
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(new CardCollection(c), ai)) {
            if (!sa.isSpell()) {
                continue;
            }
            if (x > 0) {
                sa.setXManaCostPaid(x);
            }
            if (sa.canPlay() && setTarget(sa, target) && ComputerUtilMana.canPayManaCost(sa, ai, 0, false)
                    && CostPayment.canPayAdditionalCosts(sa.getPayCosts(), sa, false, ai)) {
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
            if (!setTarget(sa, step.target())) {
                continue;
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
                if (step.color() != null && sa.getManaPart().isAnyMana()) {
                    sa.getManaPart().setExpressChoice(step.color());
                }
                return sa;
            }
        }
        return null;
    }

    /**
     * The step's mana ability of our permanent that makes the most mana, if playable now; any-colour mana is set to
     * make the step's colour.
     */
    private SpellAbility colorManaAbility(ComboStep.ManaAbility step) {
        Card c = findByName(ai.getCardsIn(ZoneType.Battlefield), step.card());
        if (c == null) {
            return null;
        }
        SpellAbility best = null;
        for (SpellAbility sa : c.getManaAbilities()) {
            sa.setActivatingPlayer(ai);
            if (sa.canPlay() && ComputerUtilCost.canPayCost(sa, ai, false)
                    && (best == null || sa.amountOfManaGenerated(true) > best.amountOfManaGenerated(true))) {
                best = sa;
            }
        }
        if (best != null && best.getManaPart().isAnyMana()) {
            // the stock choice guesses from the hand, which LED discards first and which says nothing of loop colours
            best.getManaPart().setExpressChoice(step.color());
        }
        return best;
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
                continue; // TapForMana and ManaAbility are never setup steps, so only Cast and Activate get past here
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

    private static ManaCost totalCost(Player p, List<SpellAbility> spells) {
        ManaCost total = ManaCost.ZERO;
        for (SpellAbility sa : spells) {
            ManaCostBeingPaid cost = ComputerUtilMana.calculateManaCost(sa.getPayCosts(), sa, p, true, 0, false);
            total = ManaCost.combine(total, cost.toManaCost());
        }
        return total;
    }

    /** True if {@code p}'s current sources can pay for all these spells together (colours included). Taps nothing. */
    static boolean canPayCombined(Player p, List<SpellAbility> spells) {
        if (spells.isEmpty()) {
            return true;
        }
        ManaCost total = totalCost(p, spells);
        if (ComputerUtilMana.getAvailableManaEstimate(p, false) < total.getCMC()) {
            return false; // cheap early-out before the shard-by-shard check
        }
        return ComputerUtilMana.canPayManaCost(new ManaCostBeingPaid(total), spells.get(0), p, false);
    }

    /**
     * As {@link #canPayCombined}, but without the mana of our permanent {@code excluded} (null: every source counts),
     * which is held back from the payer for the check only.
     */
    static boolean canPayCombinedWithout(Player p, List<SpellAbility> spells, Card excluded) {
        if (excluded == null || AiCardMemory.isRememberedCard(p, excluded, MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL)) {
            return canPayCombined(p, spells);
        }
        AiCardMemory.rememberCard(p, excluded, MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
        try {
            return canPayCombined(p, spells);
        } finally {
            AiCardMemory.forgetCard(p, excluded, MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
        }
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
                new ManaCostBeingPaid(totalCost(ai, later)), later.get(0), ai, false);
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
        return setup != null && canPayCombined(ai, setup);
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
    private Card tutorTarget(CardCollectionView options) {
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
            SpellAbility sa = castableSpell(c, 0, null);
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
                if (canPayCombined(ai, all) && holdManaFor(lineSpells, sa)) {
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
            if (step instanceof ComboStep.ManaAbility mana) {
                return colorManaAbility(mana);
            }
            if (step instanceof ComboStep.Cast cast) {
                Card c = findByName(ai.getCardsIn(ZoneType.Hand), cast.card());
                if (c == null) {
                    c = findByName(ai.getCardsIn(ZoneType.Graveyard), cast.card());
                }
                return c == null ? null : castableSpell(c, cast.x(), cast.target());
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
            activeLine = null;
        }
        if (actionsThisTurn >= MAX_ACTIONS_PER_TURN) {
            return null;
        }
        ComboLine line = findExecutableLine();
        SpellAbility sa = line == null ? tutorStep() : firstStep(line);
        if (sa != null) {
            actionsThisTurn++;
            lastPlayed = sa;
            activeLine = line;
        }
        return sa;
    }

    /** The resolution-time choices of the line being played, else none. */
    private ComboChoices choices() {
        return activeLine == null ? ComboChoices.NONE : activeLine.choices();
    }

    /**
     * True while the top of the stack is the ability the planner last played: the line goes on once it resolves, so
     * the AI passes instead of searching every card it could respond with. In a Knacksaw loop that search covers every
     * exiled card, once per activation.
     */
    public boolean passesOnOwnAbility() {
        if (lastPlayed == null || ai.getGame().getStack().isEmpty()) {
            return false;
        }
        SpellAbility top = ai.getGame().getStack().peekAbility();
        return top.isAbility() && top.getActivatingPlayer() == ai && top.getApi() == lastPlayed.getApi()
                && top.getHostCard().equals(lastPlayed.getHostCard());
    }

    /** True if the AI must leave our {@code c} untapped for a line that goes on in a later turn: no mana, no attack. */
    public boolean keepsUntapped(Card c) {
        return ComboLibrary.keepUntapped(ai, c);
    }

    /** Holds back the cards {@link #keepsUntapped} names from the AI's mana payments until it next chooses a spell. */
    public void holdUntappedPieces() {
        for (Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (keepsUntapped(c)) {
                AiCardMemory.rememberCard(ai, c, MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
            }
        }
    }

    /**
     * Planned targets for one of our triggers (e.g. Heliod's counter onto Ballista; Scholar's two returns; Kitten's
     * flicker onto Teferi) or for a copy of one of our spells (Brain Freeze's storm copies), or null to let the AI
     * choose: the active line's choice, else the host's {@link ComboLine#triggerTargets()} entry in the active line,
     * else in the first line that has a legal one.
     */
    public List<GameEntity> triggerTargets(SpellAbility trigger) {
        SpellAbility sa = trigger instanceof WrappedAbility wrapped ? wrapped.getWrappedAbility() : trigger;
        if (sa.getHostCard() == null || !sa.usesTargeting()) {
            return null;
        }
        List<GameEntity> chosen = choices().triggerTargets(ai, sa);
        if (chosen != null) {
            return chosen;
        }
        GameEntity target = activeLine == null ? null : mappedTarget(activeLine, sa);
        for (int i = 0; target == null && i < ComboLibrary.ALL.size(); i++) {
            target = mappedTarget(ComboLibrary.ALL.get(i), sa);
        }
        return target == null ? null : List.of(target);
    }

    /** The legal target {@code line}'s {@link ComboLine#triggerTargets()} entry gives {@code sa}'s host, or null. */
    private GameEntity mappedTarget(ComboLine line, SpellAbility sa) {
        ComboTarget planned = line.triggerTargets().get(sa.getHostCard().getName());
        GameEntity target = planned == null ? null : planned.pick(ai);
        return target != null && sa.canTarget(target) ? target : null;
    }

    /**
     * The card {@code sa} moves from a hidden zone (library, hand) to {@code destination}, or null to let the AI
     * choose: the active line's choice (the Scholar loop's Entomb), else the piece a tutor completes a line with,
     * the card a permanent imprints, or the win card Entomb buries.
     */
    public Card zoneChoice(SpellAbility sa, ZoneType destination, CardCollectionView options) {
        Card chosen = choices().hiddenZoneChoice(ai, sa, destination, options);
        if (chosen != null) {
            return chosen;
        }
        return switch (destination) {
            case Hand, Library -> tutorTarget(options);
            case Exile -> exileChoice(sa, options);
            case Graveyard -> graveyardChoice(sa, options);
            default -> null;
        };
    }

    /** The creature the active line's sacrifice cost takes from {@code options} (the nontoken Scholar), or null. */
    public Card sacrificeChoice(SpellAbility sa, CardCollectionView options) {
        return choices().sacrifice(ai, sa, options);
    }

    /** Whether to pay our optional trigger's cost for the active line (Inalla's {1}), or null to let the AI decide. */
    public Boolean payOptionalTrigger(WrappedAbility trigger) {
        return choices().payOptionalTrigger(ai, trigger);
    }

    /** The one colour of the active line's combination mana (Burnt Offering's black), or null to let the AI choose. */
    public Byte comboManaColor(SpellAbility sa) {
        return choices().comboManaColor(ai, sa);
    }

    /** The card one of our permanents should exile from {@code options} (Scepter imprints Reversal), or null. */
    private Card exileChoice(SpellAbility sa, CardCollectionView options) {
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

    /**
     * First line (in {@link ComboLibrary#ALL} order) with a step casting {@code card} and every piece in its zones.
     * Neither its condition nor its cost is checked, so it still holds while that step resolves, with the card no
     * longer in hand.
     */
    private ComboLine assembledLineCasting(String card) {
        for (ComboLine line : ComboLibrary.ALL) {
            if (line.steps().stream().anyMatch(step -> step instanceof ComboStep.Cast cast && cast.card().equals(card))
                    && line.pieces().stream().allMatch(piece -> piece.find(ai) != null)) {
                return line;
            }
        }
        return null;
    }

    /**
     * The card our line spell puts into our graveyard from {@code options}: the line's win card (Entomb buries Oracle
     * for a reanimation line), or null to let the AI choose.
     */
    private Card graveyardChoice(SpellAbility sa, CardCollectionView options) {
        Card host = sa.getHostCard();
        ComboLine line = host == null ? null : assembledLineCasting(host.getName());
        return line == null ? null : findByName(options, line.winCard());
    }

    private static Card optionNamed(Iterable<? extends GameEntity> options, String name) {
        for (GameEntity e : options) {
            if (e instanceof Card c && c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Our pick among {@code options} where the stock choice breaks a line, or null to let the AI choose:
     * <ul>
     * <li>soulbond: Deadeye Navigator pairs with the creature its line flickers (first line in order, so Shang-Chi
     * before Great Oak Guardian);</li>
     * <li>a dig onto the battlefield by a line piece (Kinnan): the first piece missing from a line that needs the
     * digger, in line order, so a Knacksaw kill piece before an engine piece.</li>
     * </ul>
     */
    public Card effectChoice(SpellAbility sa, Iterable<? extends GameEntity> options) {
        Card host = sa.getHostCard();
        if (host == null) {
            return null;
        }
        if (sa.getApi() == ApiType.Bond) {
            for (ComboLine line : ComboLibrary.ALL) {
                String wanted = line.soulbondPartners().get(host.getName());
                Card c = wanted == null ? null : optionNamed(options, wanted);
                if (c != null) {
                    return c;
                }
            }
        } else if (sa.getApi() == ApiType.Dig && ZoneType.Battlefield.name().equals(sa.getParam("DestinationZone"))) {
            for (ComboLine line : ComboLibrary.ALL) {
                if (line.pieces().stream().noneMatch(piece -> piece.card().equals(host.getName()))) {
                    continue;
                }
                for (ComboPiece piece : line.pieces()) {
                    Card c = piece.find(ai) == null ? optionNamed(options, piece.card()) : null;
                    if (c != null) {
                        return c;
                    }
                }
            }
        }
        return null;
    }

    /**
     * The cards an escape cost may exile from {@code options}: all but the Breach line's pieces, which the loop casts
     * again. Null if {@code sa} isn't cast with escape.
     */
    public CardCollection escapeExileOptions(SpellAbility sa, CardCollectionView options) {
        if (!sa.isAlternativeCost(AlternativeCost.Escape)) {
            return null;
        }
        return CardLists.filter(options, c -> !BreachLoop.PIECES.contains(c.getName()));
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

    /**
     * True while a Pact line is live, so we decline every card: Oracle in hand, or in our graveyard for a line that
     * reanimates it after Pact.
     */
    public boolean wantsToExileLibraryWithPact() {
        if (findByName(ai.getCardsIn(ZoneType.Hand), ComboLibrary.ORACLE) != null) {
            return true;
        }
        return findByName(ai.getCardsIn(ZoneType.Graveyard), ComboLibrary.ORACLE) != null
                && assembledLineCasting(ComboLibrary.TAINTED_PACT) != null;
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
