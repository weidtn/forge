package forge.ai.cedh;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import forge.ai.AiCardMemory;
import forge.ai.AiCardMemory.MemorySet;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilMana;
import forge.card.mana.ManaCost;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public final class ComboPlanner {
    private final Player ai;

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

    /** The card's first spell ability, used to price it in any zone; null if it has none. */
    private SpellAbility firstSpell(Card c) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isSpell()) {
                sa.setActivatingPlayer(ai);
                return sa;
            }
        }
        return null;
    }

    /** A spell of this card we can cast and pay for right now, or null. */
    private SpellAbility castableSpell(Card c) {
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(new CardCollection(c), ai)) {
            if (sa.isSpell() && sa.canPlay() && ComputerUtilMana.canPayManaCost(sa, ai, 0, false)) {
                return sa;
            }
        }
        return null;
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

    /** All pieces in hand, extra condition holds, and the whole line is payable with our sources. */
    boolean isExecutable(ComboLine line) {
        CardCollectionView hand = ai.getCardsIn(ZoneType.Hand);
        List<SpellAbility> spells = new ArrayList<>();
        for (String name : line.castOrder()) {
            Card c = findByName(hand, name);
            SpellAbility sa = c == null ? null : firstSpell(c);
            if (sa == null) {
                return false;
            }
            spells.add(sa);
        }
        if (!line.condition().test(ai)) {
            return false;
        }
        return canPayCombined(spells);
    }

    public ComboLine findExecutableLine() {
        for (ComboLine line : ComboLibrary.ALL) {
            if (isExecutable(line)) {
                return line;
            }
        }
        return null;
    }

    private static List<String> missingFrom(ComboLine line, CardCollectionView hand) {
        List<String> missing = new ArrayList<>();
        for (String name : line.castOrder()) {
            if (findByName(hand, name) == null) {
                missing.add(name);
            }
        }
        return missing;
    }

    /**
     * First line (in {@link ComboLibrary#ALL} order) that lacks exactly one piece in hand, provided that piece is in
     * our library and the line's condition holds; else null.
     */
    private ComboLine lineMissingOnePiece() {
        CardCollectionView hand = ai.getCardsIn(ZoneType.Hand);
        CardCollectionView lib = ai.getCardsIn(ZoneType.Library);
        for (ComboLine line : ComboLibrary.ALL) {
            List<String> missing = missingFrom(line, hand);
            if (missing.size() == 1 && findByName(lib, missing.get(0)) != null && line.condition().test(ai)) {
                return line;
            }
        }
        return null;
    }

    /** Name of the single library card that would complete a line (see {@link #lineMissingOnePiece()}), or null. */
    public String missingPiece() {
        ComboLine line = lineMissingOnePiece();
        return line == null ? null : missingFrom(line, ai.getCardsIn(ZoneType.Hand)).get(0);
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
        Card target = findByName(lib, missingFrom(line, hand).get(0));
        boolean alreadyOnTop = lib.get(0) == target;
        SpellAbility toTop = null;
        for (Card c : hand) {
            Predicate<Card> canFind = ComboLibrary.TUTORS.get(c.getName());
            if (canFind == null || !canFind.test(target)) {
                continue;
            }
            SpellAbility sa = castableSpell(c);
            if (sa == null) {
                continue;
            }
            if (ZoneType.Hand.name().equals(sa.getParam("Destination"))) {
                List<SpellAbility> lineSpells = new ArrayList<>();
                for (String name : line.castOrder()) {
                    Card piece = findByName(hand, name);
                    SpellAbility pieceSa = firstSpell(piece != null ? piece : target);
                    if (pieceSa == null) {
                        return null;
                    }
                    lineSpells.add(pieceSa);
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
     * Next spell to cast: the first step of an executable line, else a tutor for the missing piece, or null.
     * Only acts on an empty stack (MVP).
     */
    public SpellAbility nextStep() {
        if (!ai.getGame().getStack().isEmpty()) {
            return null;
        }
        ComboLine line = findExecutableLine();
        if (line == null) {
            return tutorStep();
        }
        return castableSpell(findByName(ai.getCardsIn(ZoneType.Hand), line.castOrder().get(0)));
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
            if (ComboLibrary.TUTORS.containsKey(name) || ComboLibrary.ALL.stream().anyMatch(l -> l.castOrder().contains(name))) {
                hasPiece = true;
            }
            if (c.isLand() || !c.getManaAbilities().isEmpty()) {
                sources++;
            }
        }
        return hasPiece && sources >= 2;
    }
}
