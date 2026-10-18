package forge.ai.cedh;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class ComboPlannerTest extends AITest {

    /** p = players.get(1), MAIN1, cEDH profile, library of 20 bears. */
    private Player setup(Game game) {
        Player p = game.getPlayers().get(1);
        ((LobbyPlayerAi) p.getLobbyPlayer()).setAiProfile("cEDH");
        fillLibrary(p, 20);
        return p;
    }

    private ComboPlanner planner(Player p) {
        return ((PlayerControllerAi) p.getController()).getAi().getComboPlanner();
    }

    @Test
    public void picksConsultationWhenOracleLineIsAffordable() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        addCards("Island", 2, p);
        addCard("Swamp", p);
        addCardToZone(ComboLibrary.CONSULTATION, p, ZoneType.Hand);
        addCardToZone(ComboLibrary.ORACLE, p, ZoneType.Hand);

        SpellAbility sa = planner(p).nextStep();

        AssertJUnit.assertNotNull(sa);
        AssertJUnit.assertEquals(ComboLibrary.CONSULTATION, sa.getHostCard().getName());
    }

    @Test
    public void nothingWhenOnlyTwoLands() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        addCard("Island", p);
        addCard("Swamp", p);
        addCardToZone(ComboLibrary.CONSULTATION, p, ZoneType.Hand);
        addCardToZone(ComboLibrary.ORACLE, p, ZoneType.Hand);
        AssertJUnit.assertNull(planner(p).nextStep());
    }

    @Test
    public void nothingWhenColoursDontFit() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        addCards("Swamp", 2, p);
        addCard("Island", p);
        addCardToZone(ComboLibrary.CONSULTATION, p, ZoneType.Hand);
        addCardToZone(ComboLibrary.ORACLE, p, ZoneType.Hand);
        AssertJUnit.assertNull(planner(p).nextStep());
    }

    @Test
    public void nothingWithoutOracle() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        addCards("Island", 2, p);
        addCard("Swamp", p);
        addCardToZone(ComboLibrary.CONSULTATION, p, ZoneType.Hand);
        AssertJUnit.assertNull(planner(p).nextStep());
    }

    @Test
    public void taintedPactRejectedWithDuplicateNamesInLibrary() {
        Game game = initAndCreateGame();
        Player p = setup(game);           // 20x Runeclaw Bear = duplicates
        addCards("Island", 2, p);
        addCards("Swamp", 2, p);
        addCardToZone(ComboLibrary.TAINTED_PACT, p, ZoneType.Hand);
        addCardToZone(ComboLibrary.ORACLE, p, ZoneType.Hand);
        AssertJUnit.assertNull(planner(p).nextStep());
    }

    @Test
    public void consultationNamesOracle() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        addCards("Island", 2, p);
        addCard("Swamp", p);
        addCardToZone(ComboLibrary.CONSULTATION, p, ZoneType.Hand);
        addCardToZone(ComboLibrary.ORACLE, p, ZoneType.Hand);
        AssertJUnit.assertEquals(ComboLibrary.ORACLE, planner(p).consultationName());
    }

    private void playUntilGameOverOrPhase(Game game, PhaseType stop, int maxSteps) {
        for (int i = 0; i < maxSteps && !game.isGameOver(); i++) {
            game.getPhaseHandler().mainLoopStep();
            if (game.getPhaseHandler().is(stop)) {
                break;
            }
        }
    }

    @Test
    public void winsWithConsultationOracle() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        addCards("Island", 2, p);
        addCard("Swamp", p);
        addCardToZone(ComboLibrary.CONSULTATION, p, ZoneType.Hand);
        addCardToZone(ComboLibrary.ORACLE, p, ZoneType.Hand);

        playUntilGameOverOrPhase(game, PhaseType.END_OF_TURN, 500);

        AssertJUnit.assertTrue("game should be over", game.isGameOver());
        AssertJUnit.assertTrue("p should have won", p.hasWon());
    }

    @Test
    public void winsWithTaintedPactOracle() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        ((LobbyPlayerAi) p.getLobbyPlayer()).setAiProfile("cEDH");
        for (String n : new String[]{"Runeclaw Bear", "Grizzly Bears", "Llanowar Elves", "Elvish Mystic", "Fyndhorn Elves"}) {
            addCardToZone(n, p, ZoneType.Library);
        }
        addCards("Island", 2, p);
        addCards("Swamp", 2, p);
        addCardToZone(ComboLibrary.TAINTED_PACT, p, ZoneType.Hand);
        addCardToZone(ComboLibrary.ORACLE, p, ZoneType.Hand);

        playUntilGameOverOrPhase(game, PhaseType.END_OF_TURN, 500);

        AssertJUnit.assertTrue(game.isGameOver());
        AssertJUnit.assertTrue(p.hasWon());
    }

    @Test
    public void winsWithDemonicTutorForOracle() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        addCardToZone(ComboLibrary.ORACLE, p, ZoneType.Library);
        addCards("Island", 2, p);
        addCards("Swamp", 3, p);
        addCardToZone("Demonic Tutor", p, ZoneType.Hand);
        addCardToZone(ComboLibrary.CONSULTATION, p, ZoneType.Hand);

        playUntilGameOverOrPhase(game, PhaseType.END_OF_TURN, 500);

        AssertJUnit.assertTrue("game should be over", game.isGameOver());
        AssertJUnit.assertTrue("p should have won", p.hasWon());
    }

    @Test
    public void vampiricTutorPutsOracleOnTop() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        addCardToZone(ComboLibrary.ORACLE, p, ZoneType.Library); // bottom: below the 20 bears
        addCards("Island", 2, p);
        addCards("Swamp", 2, p);
        addCardToZone("Vampiric Tutor", p, ZoneType.Hand);
        addCardToZone(ComboLibrary.CONSULTATION, p, ZoneType.Hand);

        for (int i = 0; i < 500 && !game.isGameOver()
                && countCardsWithName(game, "Vampiric Tutor", ZoneType.Graveyard) == 0; i++) {
            game.getPhaseHandler().mainLoopStep();
        }

        AssertJUnit.assertEquals("Vampiric Tutor should have resolved",
                1, countCardsWithName(game, "Vampiric Tutor", ZoneType.Graveyard));
        AssertJUnit.assertEquals(ComboLibrary.ORACLE, p.getCardsIn(ZoneType.Library).get(0).getName());
    }

    @Test
    public void heliodLineFirstGivesBallistaLifelink() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        addCards("Plains", 2, p);
        addCard("Heliod, Sun-Crowned", p);
        Card ballista = addCard("Walking Ballista", p);
        ballista.setCounters(CounterEnumType.P1P1, 2);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = planner(p).nextStep();

        AssertJUnit.assertNotNull(sa);
        AssertJUnit.assertEquals("Heliod, Sun-Crowned", sa.getHostCard().getName());
        AssertJUnit.assertEquals(ApiType.Pump, sa.getApi());
        AssertJUnit.assertEquals(ballista, sa.getTargets().getFirstTargetedCard());
    }

    @Test
    public void nothingWhenBallistaWouldDieToItsOwnPing() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        addCards("Plains", 2, p);          // pays the lifelink pump, not Ballista's {4}
        addCard("Heliod, Sun-Crowned", p);
        Card ballista = addCard("Walking Ballista", p);
        ballista.setCounters(CounterEnumType.P1P1, 1);
        game.getAction().checkStateEffects(true);

        AssertJUnit.assertNull(planner(p).nextStep());
    }

    @Test
    public void winsWithHeliodBallista() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        Player opp = game.getPlayers().get(0);
        addCards("Plains", 2, p);
        addCard("Heliod, Sun-Crowned", p);
        Card ballista = addCard("Walking Ballista", p);
        ballista.setCounters(CounterEnumType.P1P1, 2);
        addCard("Colossal Dreadmaw", p);   // stock trigger AI would put Heliod's counters here
        game.getAction().checkStateEffects(true);

        playUntilGameOverOrPhase(game, PhaseType.END_OF_TURN, 3000);

        AssertJUnit.assertTrue("game should be over", game.isGameOver());
        AssertJUnit.assertTrue("opponent should have lost", opp.hasLost());
        AssertJUnit.assertTrue("p should have won", p.hasWon());
    }

    @Test
    public void keepsHandWithPieceAndTwoLands() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        addCardToZone(ComboLibrary.ORACLE, p, ZoneType.Hand);
        addCardToZone("Island", p, ZoneType.Hand);
        addCardToZone("Swamp", p, ZoneType.Hand);
        for (int i = 0; i < 4; i++) {
            addCardToZone("Runeclaw Bear", p, ZoneType.Hand);
        }
        AssertJUnit.assertTrue(planner(p).keepHand());
    }

    @Test
    public void mulligansHandWithoutPieces() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        addCardToZone("Island", p, ZoneType.Hand);
        addCardToZone("Swamp", p, ZoneType.Hand);
        addCardToZone("Swamp", p, ZoneType.Hand);
        for (int i = 0; i < 4; i++) {
            addCardToZone("Runeclaw Bear", p, ZoneType.Hand);
        }
        AssertJUnit.assertFalse(planner(p).keepHand());
    }
}
