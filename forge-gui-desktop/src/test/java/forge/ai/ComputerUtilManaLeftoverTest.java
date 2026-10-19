package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * determineLeftoverMana makes one test payment per candidate X and hands the mana sources grouped for the first one
 * to the others. That grouping must not outlive the call: the next call has to see the board as it is then.
 */
public class ComputerUtilManaLeftoverTest extends AITest {

    @Test
    public void leftoverManaFollowsTheBoardFromOneCallToTheNext() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);

        Card mountain = addCards("Mountain", 3, ai).get(0);
        addCard("Shivan Reef", ai); // its colored mana ability has a sub ability: the pain
        Card blaze = addCardToZone("Blaze", ai, ZoneType.Hand); // {X}{R}
        SpellAbility sa = blaze.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);

        AssertJUnit.assertEquals(3, ComputerUtilMana.determineLeftoverMana(sa, ai, false));

        mountain.setTapped(true);
        AssertJUnit.assertEquals(2, ComputerUtilMana.determineLeftoverMana(sa, ai, false));
    }
}
