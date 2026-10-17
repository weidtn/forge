package forge.ai.cedh;

import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilMana;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
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

    /** All pieces in hand, extra condition holds, total CMC <= available mana (colour check is per step). */
    boolean isExecutable(ComboLine line) {
        CardCollectionView hand = ai.getCardsIn(ZoneType.Hand);
        int totalCmc = 0;
        for (String name : line.castOrder()) {
            Card c = findByName(hand, name);
            if (c == null) {
                return false;
            }
            totalCmc += c.getCMC();
        }
        if (!line.condition().test(ai)) {
            return false;
        }
        return ComputerUtilMana.getAvailableManaEstimate(ai, false) >= totalCmc;
    }

    public ComboLine findExecutableLine() {
        for (ComboLine line : ComboLibrary.ALL) {
            if (isExecutable(line)) {
                return line;
            }
        }
        return null;
    }

    /** Next spell to cast for the first executable line, or null. Only acts on an empty stack (MVP). */
    public SpellAbility nextStep() {
        if (!ai.getGame().getStack().isEmpty()) {
            return null;
        }
        ComboLine line = findExecutableLine();
        if (line == null) {
            return null;
        }
        Card first = findByName(ai.getCardsIn(ZoneType.Hand), line.castOrder().get(0));
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(new CardCollection(first), ai)) {
            if (sa.isSpell() && sa.canPlay() && ComputerUtilMana.canPayManaCost(sa, ai, 0, false)) {
                return sa;
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
}
