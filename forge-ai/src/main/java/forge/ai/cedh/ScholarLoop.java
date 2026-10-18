package forge.ai.cedh;

import static forge.ai.cedh.ComboLibrary.BURNT_OFFERING;
import static forge.ai.cedh.ComboLibrary.CONSULTATION;
import static forge.ai.cedh.ComboLibrary.CULLING;
import static forge.ai.cedh.ComboLibrary.ENTOMB;
import static forge.ai.cedh.ComboLibrary.INALLA;
import static forge.ai.cedh.ComboLibrary.ORACLE;
import static forge.ai.cedh.ComboLibrary.SACRIFICE;
import static forge.ai.cedh.ComboLibrary.SCHOLAR;
import static forge.ai.cedh.ComboLibrary.SHALLOW_GRAVE;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.card.MagicColor;
import forge.game.GameEntity;
import forge.game.ability.AbilityKey;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;

/**
 * Inalla + Scholar of the Ages + Shallow Grave + an outlet + Entomb, reduced to the zones of its cards and our mana,
 * and played forward with the line's own step rules (first that holds):
 * <ol>
 * <li>Shallow Grave returns Oracle, the top creature card of our graveyard, onto an empty library: the win.</li>
 * <li>Consultation exiles the library, Oracle being the top creature card and Shallow Grave in hand.</li>
 * <li>Entomb buries Oracle, with Consultation and Shallow Grave in hand and {4} for the three casts.</li>
 * <li>An outlet sacrifices the nontoken Scholar: Burnt Offering and Sacrifice make 7 black, Culling the Weak 4.</li>
 * <li>Entomb buries Consultation while Scholar is in the graveyard.</li>
 * <li>Shallow Grave returns Scholar, the top creature card. Its trigger returns two of
 * {@link #RETURN_PRIORITY} from the graveyard; Inalla's {1} is paid if a step is still payable afterwards, and the
 * token's trigger returns two more.</li>
 * <li>Entomb buries Scholar.</li>
 * </ol>
 * The planner starts the loop only if this reaches the win within {@link #MAX_ACTIONS}; the resolution-time choices
 * ({@link #CHOICES}) follow the same rules, so the two can't drift apart.
 */
final class ScholarLoop {
    /** Best first: Burnt Offering and Sacrifice make Scholar's mana value, Culling the Weak only four. */
    static final List<String> OUTLETS = List.of(BURNT_OFFERING, SACRIFICE, CULLING);
    /** What Scholar's enter triggers return, best first. */
    private static final List<String> RETURN_PRIORITY = List.of(SHALLOW_GRAVE, BURNT_OFFERING, SACRIFICE, CULLING, ENTOMB,
            CONSULTATION);

    private static final List<String> TRACKED = List.of(SCHOLAR, SHALLOW_GRAVE, BURNT_OFFERING, SACRIFICE, CULLING,
            ENTOMB, CONSULTATION, ORACLE);
    /** Bound on projected actions; the tightest kill takes 13. */
    private static final int MAX_ACTIONS = 40;
    private static final int SCHOLAR_MANA_VALUE = 7;
    private static final int CULLING_MANA = 4;
    /** Scholar returns "up to two" cards. */
    private static final int RETURNS = 2;

    private enum Where { HAND, LIBRARY, GRAVEYARD, BATTLEFIELD, ELSEWHERE }

    /** A step: the card it casts (null: an outlet) and the card Entomb buries (null: not Entomb). */
    private enum Action {
        REANIMATE_ORACLE(SHALLOW_GRAVE, null),
        EXILE_LIBRARY(CONSULTATION, null),
        ENTOMB_ORACLE(ENTOMB, ORACLE),
        SACRIFICE_SCHOLAR(null, null),
        ENTOMB_CONSULTATION(ENTOMB, CONSULTATION),
        REANIMATE_SCHOLAR(SHALLOW_GRAVE, null),
        ENTOMB_SCHOLAR(ENTOMB, SCHOLAR);

        private final String cast;
        private final String buries;

        Action(String cast, String buries) {
            this.cast = cast;
            this.buries = buries;
        }
    }

    private final Map<String, Where> zones;
    /** Names of the creature cards in our graveyard, bottom to top. */
    private final List<String> graveyardCreatures;
    private final boolean eminence;
    private int library;
    private int black;
    private int other;

    private ScholarLoop(Map<String, Where> zones, List<String> graveyardCreatures, boolean eminence) {
        this.zones = zones;
        this.graveyardCreatures = graveyardCreatures;
        this.eminence = eminence;
    }

    private ScholarLoop copy() {
        ScholarLoop s = new ScholarLoop(new HashMap<>(zones), new ArrayList<>(graveyardCreatures), eminence);
        s.library = library;
        s.black = black;
        s.other = other;
        return s;
    }

    /**
     * The board as the loop sees it: a spell on the stack counts as in the graveyard, a card one of Scholar's triggers
     * on the stack targets as in hand. Mana is the pool plus what our untapped {T} sources make, black if they can.
     */
    static ScholarLoop of(Player p) {
        Map<String, Where> zones = new HashMap<>();
        for (String name : TRACKED) {
            zones.put(name, where(p, name));
        }
        List<String> creatures = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Graveyard)) {
            if (c.isCreature()) {
                creatures.add(c.getName());
            }
        }
        ScholarLoop s = new ScholarLoop(zones, creatures, ComboPiece.inCommandOrPlay(INALLA).find(p) != null);
        s.library = p.getCardsIn(ZoneType.Library).size();
        s.black = p.getManaPool().getAmountOfColor(MagicColor.BLACK);
        s.other = p.getManaPool().totalMana() - s.black;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            boolean canTap = c.isUntapped() && !(c.isCreature() && c.isAbilitySick());
            SpellAbility mana = canTap ? ComboPlanner.tapManaAbility(c, p) : null;
            if (mana == null) {
                continue;
            }
            if (c.getManaAbilities().anyMatch(m -> m.canProduce("B"))) {
                s.black += mana.amountOfManaGenerated(true);
            } else {
                s.other += mana.amountOfManaGenerated(true);
            }
        }
        for (SpellAbilityStackInstance si : p.getGame().getStack()) {
            SpellAbility sa = unwrap(si.getSpellAbility());
            if (isScholarTrigger(p, sa)) {
                for (Card c : sa.getTargets().getTargetCards()) {
                    if (zones.get(c.getName()) == Where.GRAVEYARD) {
                        zones.put(c.getName(), Where.HAND);
                    }
                }
            }
        }
        return s;
    }

    private static SpellAbility unwrap(SpellAbility sa) {
        return sa instanceof WrappedAbility wrapped ? wrapped.getWrappedAbility() : sa;
    }

    private static boolean isNamed(Card c, String name) {
        return c != null && c.getName().equals(name);
    }

    /** One of our Scholars' (the token's too) enter trigger, returning cards from our graveyard. */
    private static boolean isScholarTrigger(Player p, SpellAbility sa) {
        return sa.getApi() == ApiType.ChangeZone && isNamed(sa.getHostCard(), SCHOLAR) && sa.getActivatingPlayer() == p;
    }

    /** Our nontoken copy of the card; a spell on the stack is where it lands. */
    private static Where where(Player p, String name) {
        for (ZoneType zone : new ZoneType[] {ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard, ZoneType.Battlefield,
                ZoneType.Stack}) {
            for (Card c : p.getCardsIn(zone)) {
                if (c.getName().equals(name) && !c.isToken()) {
                    return switch (zone) {
                        case Hand -> Where.HAND;
                        case Library -> Where.LIBRARY;
                        case Battlefield -> Where.BATTLEFIELD;
                        default -> Where.GRAVEYARD;
                    };
                }
            }
        }
        return Where.ELSEWHERE;
    }

    private boolean in(String name, Where zone) {
        return zones.get(name) == zone;
    }

    private String topCreature() {
        return graveyardCreatures.isEmpty() ? null : graveyardCreatures.get(graveyardCreatures.size() - 1);
    }

    private void move(String name, Where to) {
        if (zones.put(name, to) == Where.GRAVEYARD) {
            graveyardCreatures.remove(name);
        }
        if (to == Where.GRAVEYARD && (name.equals(SCHOLAR) || name.equals(ORACLE))) {
            graveyardCreatures.add(name);
        }
    }

    private boolean canPay(int generic, int blackShards) {
        return black >= blackShards && black - blackShards + other >= generic;
    }

    /** Generic is paid with non-black mana first, as the AI's payment does. */
    private void pay(int generic, int blackShards) {
        black -= blackShards;
        int fromOther = Math.min(other, generic);
        other -= fromOther;
        black -= generic - fromOther;
    }

    private String outletInHand() {
        for (String outlet : OUTLETS) {
            if (in(outlet, Where.HAND)) {
                return outlet;
            }
        }
        return null;
    }

    /** The line's next step, or null if none is payable. */
    private Action next() {
        boolean graveInHand = in(SHALLOW_GRAVE, Where.HAND);
        boolean entombInHand = in(ENTOMB, Where.HAND);
        String top = topCreature();
        if (graveInHand && ORACLE.equals(top) && library == 0 && canPay(1, 1)) {
            return Action.REANIMATE_ORACLE;
        }
        if (in(CONSULTATION, Where.HAND) && graveInHand && ORACLE.equals(top) && canPay(1, 2)) {
            return Action.EXILE_LIBRARY;
        }
        if (entombInHand && in(CONSULTATION, Where.HAND) && graveInHand && in(ORACLE, Where.LIBRARY) && canPay(1, 3)) {
            return Action.ENTOMB_ORACLE;
        }
        if (in(SCHOLAR, Where.BATTLEFIELD) && outletInHand() != null && canPay(0, 1)) {
            return Action.SACRIFICE_SCHOLAR;
        }
        if (entombInHand && in(CONSULTATION, Where.LIBRARY) && in(SCHOLAR, Where.GRAVEYARD) && canPay(0, 1)) {
            return Action.ENTOMB_CONSULTATION;
        }
        if (graveInHand && SCHOLAR.equals(top) && canPay(1, 1)) {
            return Action.REANIMATE_SCHOLAR;
        }
        if (entombInHand && in(SCHOLAR, Where.LIBRARY) && canPay(0, 1)) {
            return Action.ENTOMB_SCHOLAR;
        }
        return null;
    }

    /** One Scholar enter trigger: the first two of {@link #RETURN_PRIORITY} in the graveyard go to hand. */
    private void returnTargets() {
        int returned = 0;
        for (String name : RETURN_PRIORITY) {
            if (returned < RETURNS && in(name, Where.GRAVEYARD)) {
                move(name, Where.HAND);
                returned++;
            }
        }
    }

    /** Inalla's {1} for the Scholar that just entered (its own trigger's cards counted as returned). */
    private boolean payEminence() {
        if (!eminence || !canPay(1, 0)) {
            return false;
        }
        ScholarLoop paid = copy();
        paid.pay(1, 0);
        paid.returnTargets();
        return paid.next() != null;
    }

    private void apply(Action a) {
        switch (a) {
            case EXILE_LIBRARY -> {
                pay(0, 1);
                move(CONSULTATION, Where.GRAVEYARD);
                for (String name : TRACKED) {
                    if (in(name, Where.LIBRARY)) {
                        move(name, Where.ELSEWHERE);
                    }
                }
                library = 0;
            }
            case ENTOMB_ORACLE, ENTOMB_CONSULTATION, ENTOMB_SCHOLAR -> {
                pay(0, 1);
                move(ENTOMB, Where.GRAVEYARD);
                move(a.buries, Where.GRAVEYARD);
                library--;
            }
            case SACRIFICE_SCHOLAR -> {
                String outlet = outletInHand();
                pay(0, 1);
                move(outlet, Where.GRAVEYARD);
                move(SCHOLAR, Where.GRAVEYARD);
                black += outlet.equals(CULLING) ? CULLING_MANA : SCHOLAR_MANA_VALUE;
            }
            case REANIMATE_SCHOLAR -> {
                pay(1, 1);
                move(SHALLOW_GRAVE, Where.GRAVEYARD); // before Scholar's trigger takes its targets
                move(SCHOLAR, Where.BATTLEFIELD);
                returnTargets();
                if (payEminence()) {
                    pay(1, 0);
                    returnTargets();
                }
            }
            default -> throw new IllegalStateException("the win isn't played forward: " + a);
        }
    }

    /** Plays the loop forward with the step rules; true if Oracle is reanimated onto an empty library. */
    boolean finishes() {
        ScholarLoop s = copy();
        for (int i = 0; i < MAX_ACTIONS; i++) {
            Action a = s.next();
            if (a == null) {
                return false;
            }
            if (a == Action.REANIMATE_ORACLE) {
                return true;
            }
            s.apply(a);
        }
        return false;
    }

    /** True if the line's next step casts {@code card}. */
    boolean casts(String card) {
        Action a = next();
        if (a == null) {
            return false;
        }
        return card.equals(a == Action.SACRIFICE_SCHOLAR ? outletInHand() : a.cast);
    }

    /**
     * The card the resolving Entomb buries: what the step that cast it was for (Entomb counted back in hand, its {B}
     * unspent). Null if that step wasn't an Entomb.
     */
    private static Card entombChoice(Player p, CardCollectionView options) {
        ScholarLoop s = of(p);
        s.zones.put(ENTOMB, Where.HAND);
        s.black++;
        Action a = s.next();
        return a == null || a.buries == null ? null : ComboPlanner.findByName(options, a.buries);
    }

    /**
     * Targets of one of our Scholar's enter triggers: the first of {@link #RETURN_PRIORITY} in our graveyard that no
     * item of ours on the stack targets yet (the original trigger's, when the token's chooses), at most the
     * trigger's maximum. Null if there are none.
     */
    private static List<GameEntity> scholarTargets(Player p, SpellAbility trigger) {
        SpellAbility sa = unwrap(trigger);
        if (!isScholarTrigger(p, sa) || sa.getTargetRestrictions() == null) {
            return null;
        }
        Set<Card> taken = new HashSet<>();
        for (SpellAbilityStackInstance si : p.getGame().getStack()) {
            for (SpellAbility s = si.getSpellAbility(); s != null; s = s.getSubAbility()) {
                if (s.getActivatingPlayer() == p) {
                    for (Card c : s.getTargets().getTargetCards()) {
                        taken.add(c);
                    }
                }
            }
        }
        int max = sa.getTargetRestrictions().getMaxTargets(sa.getHostCard(), sa);
        List<GameEntity> targets = new ArrayList<>();
        for (String name : RETURN_PRIORITY) {
            Card c = ComboPlanner.findByName(p.getCardsIn(ZoneType.Graveyard), name);
            if (targets.size() < max && c != null && !taken.contains(c) && sa.canTarget(c)) {
                targets.add(c);
            }
        }
        return targets.isEmpty() ? null : targets;
    }

    /** The loop's resolution-time choices; each one leaves every other card's choice to the stock AI. */
    static final ComboChoices CHOICES = new ComboChoices() {
        @Override
        public List<GameEntity> triggerTargets(Player ai, SpellAbility trigger) {
            return scholarTargets(ai, trigger);
        }

        /** Entomb buries what the loop needs next; the stock choice is the highest mana value. */
        @Override
        public Card hiddenZoneChoice(Player ai, SpellAbility sa, ZoneType destination, CardCollectionView options) {
            return destination == ZoneType.Graveyard && isNamed(sa.getHostCard(), ENTOMB)
                    ? entombChoice(ai, options) : null;
        }

        /** The outlet sacrifices the nontoken Scholar, which Shallow Grave returns; the stock choice is the token. */
        @Override
        public Card sacrifice(Player ai, SpellAbility sa, CardCollectionView options) {
            if (sa.getHostCard() == null || !OUTLETS.contains(sa.getHostCard().getName())) {
                return null;
            }
            for (Card c : options) {
                if (c.getName().equals(SCHOLAR) && !c.isToken()) {
                    return c;
                }
            }
            return null;
        }

        /** Inalla's {1} for the nontoken Scholar only if a step is still payable afterwards; stock always pays. */
        @Override
        public Boolean payOptionalTrigger(Player ai, WrappedAbility trigger) {
            if (!isNamed(trigger.getHostCard(), INALLA)
                    || !(trigger.getTriggeringObject(AbilityKey.Card) instanceof Card entered)
                    || !entered.getName().equals(SCHOLAR) || entered.isToken()) {
                return null;
            }
            return of(ai).payEminence();
        }

        /** The outlet's mana is all black: every spell of the loop costs {B}. */
        @Override
        public Byte comboManaColor(Player ai, SpellAbility sa) {
            return sa.getHostCard() != null && OUTLETS.contains(sa.getHostCard().getName()) ? MagicColor.BLACK : null;
        }
    };
}
