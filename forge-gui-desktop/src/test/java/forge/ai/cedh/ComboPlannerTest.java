package forge.ai.cedh;

import java.util.ArrayList;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityKey;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardLists;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
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
    public void winsWithEntombConsultationShallowGrave() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        Player opp = game.getPlayers().get(0);
        addCardToZone("Hoarding Broodlord", p, ZoneType.Library);   // mana value 8: the stock Entomb choice
        addCardToZone(ComboLibrary.ORACLE, p, ZoneType.Library);
        addCards("Swamp", 4, p);
        addCardToZone("Entomb", p, ZoneType.Hand);
        addCardToZone(ComboLibrary.CONSULTATION, p, ZoneType.Hand);
        addCardToZone("Shallow Grave", p, ZoneType.Hand);

        playUntilGameOverOrPhase(game, PhaseType.END_OF_TURN, 500);

        AssertJUnit.assertTrue("game should be over", game.isGameOver());
        AssertJUnit.assertTrue("opponent should have lost", opp.hasLost());
        AssertJUnit.assertTrue("p should have won", p.hasWon());
        AssertJUnit.assertEquals("Entomb took Oracle", 0,
                countCardsWithName(game, "Hoarding Broodlord", ZoneType.Graveyard));
    }

    @Test
    public void oracleReanimationWaitsUntilTheWholeLineIsPayable() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        addCardToZone(ComboLibrary.ORACLE, p, ZoneType.Library);
        addCards("Swamp", 3, p);            // Entomb and Consultation, then nothing left for Shallow Grave's {1}{B}
        addCardToZone("Entomb", p, ZoneType.Hand);
        addCardToZone(ComboLibrary.CONSULTATION, p, ZoneType.Hand);
        addCardToZone("Shallow Grave", p, ZoneType.Hand);

        AssertJUnit.assertNull("starting would exile our library with Oracle stuck in the graveyard",
                planner(p).nextStep());

        addCard("Swamp", p);
        SpellAbility sa = planner(p).nextStep();

        AssertJUnit.assertNotNull(sa);
        AssertJUnit.assertEquals("Entomb", sa.getHostCard().getName());
    }

    /**
     * Inalla in the command zone; Entomb, Shallow Grave and Burnt Offering in hand; Intuition (mana value 3, the stock
     * pick for Scholar's trigger) in the graveyard; Scholar, Oracle, Consultation and Hoarding Broodlord (mana value 8,
     * the stock Entomb choice) under the bears; {@code swamps} Swamps and one Wastes.
     */
    private void setupScholarLoop(Player p, int swamps) {
        addCardToZone("Inalla, Archmage Ritualist", p, ZoneType.Command);
        for (String name : List.of("Scholar of the Ages", ComboLibrary.ORACLE, ComboLibrary.CONSULTATION,
                "Hoarding Broodlord")) {
            addCardToZone(name, p, ZoneType.Library);
        }
        addCardToZone("Intuition", p, ZoneType.Graveyard);
        addCards("Swamp", swamps, p);
        addCard("Wastes", p);
        addCardToZone("Entomb", p, ZoneType.Hand);
        addCardToZone("Shallow Grave", p, ZoneType.Hand);
        addCardToZone("Burnt Offering", p, ZoneType.Hand);
    }

    @Test
    public void scholarLoopFindsConsultationAndWins() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        Player opp = game.getPlayers().get(0);
        setupScholarLoop(p, 3);             // {B}{B}{B}{1}: Inalla's first {1} would leave nothing for Burnt Offering

        playUntilGameOverOrPhase(game, PhaseType.END_OF_TURN, 1000);

        AssertJUnit.assertTrue("game should be over", game.isGameOver());
        AssertJUnit.assertTrue("opponent should have lost", opp.hasLost());
        AssertJUnit.assertTrue("p should have won", p.hasWon());
        AssertJUnit.assertEquals("Scholar's triggers never took the decoy", 1,
                countCardsWithName(game, "Intuition", ZoneType.Graveyard));
    }

    @Test
    public void scholarLoopSacrificesTheNontokenScholar() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        Player opp = game.getPlayers().get(0);
        setupScholarLoop(p, 4);             // a spare {B}: Inalla's first token is paid, and Consultation is still to find

        playUntilGameOverOrPhase(game, PhaseType.END_OF_TURN, 1000);

        AssertJUnit.assertTrue("game should be over", game.isGameOver());
        AssertJUnit.assertTrue("opponent should have lost", opp.hasLost());
        AssertJUnit.assertTrue("p should have won", p.hasWon());
    }

    @Test
    public void nothingWhenScholarLoopCannotReachTheKill() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        setupScholarLoop(p, 2);             // Entomb and Shallow Grave leave nothing for Burnt Offering

        AssertJUnit.assertNull(planner(p).nextStep());
    }

    @Test
    public void scholarTriggersSplitTheirTargets() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        addCardToZone("Inalla, Archmage Ritualist", p, ZoneType.Command);
        addCards("Swamp", 4, p);            // with 3, Inalla's {1} would leave nothing for Burnt Offering: no token
        for (String name : List.of("Burnt Offering", "Entomb", ComboLibrary.CONSULTATION, "Intuition",
                "Scholar of the Ages")) {
            addCardToZone(name, p, ZoneType.Graveyard);   // Scholar is the top creature card
        }
        addCardToZone("Shallow Grave", p, ZoneType.Hand);
        addCardToZone(ComboLibrary.ORACLE, p, ZoneType.Library);

        SpellAbility first = planner(p).nextStep();
        AssertJUnit.assertNotNull(first);
        AssertJUnit.assertEquals("Shallow Grave", first.getHostCard().getName());

        for (int i = 0; i < 200 && countCardsWithName(game, "Scholar of the Ages", ZoneType.Battlefield) == 0; i++) {
            game.getPhaseHandler().mainLoopStep();
        }
        playUntilStackClear(game);          // eminence, then the token's trigger, then the original's

        for (String name : List.of("Shallow Grave", "Burnt Offering", "Entomb", ComboLibrary.CONSULTATION)) {
            AssertJUnit.assertEquals(name + " returned", 1, countCardsWithName(game, name, ZoneType.Hand));
        }
        AssertJUnit.assertEquals("decoy stays", 1, countCardsWithName(game, "Intuition", ZoneType.Graveyard));
        AssertJUnit.assertEquals("Inalla's {1} paid", 1, CardLists.count(p.getCardsIn(ZoneType.Battlefield),
                c -> c.isToken() && c.getName().equals("Scholar of the Ages")));
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
    public void scepterImprintsReversalOverCheaperInstant() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        addCards("Island", 2, p);
        addCard("Sol Ring", p);
        addCard("Aetherflux Reservoir", p);
        addCardToZone("Isochron Scepter", p, ZoneType.Hand);
        addCardToZone("Dramatic Reversal", p, ZoneType.Hand);
        addCardToZone("Brainstorm", p, ZoneType.Hand);   // mana value 1: the stock imprint choice

        SpellAbility first = planner(p).nextStep();
        AssertJUnit.assertNotNull(first);
        AssertJUnit.assertEquals("Isochron Scepter", first.getHostCard().getName());

        for (int i = 0; i < 200 && countCardsWithName(game, "Isochron Scepter") == 0; i++) {
            game.getPhaseHandler().mainLoopStep();
        }
        playUntilStackClear(game);          // imprint trigger resolves

        Card scepter = findCardWithName(game, "Isochron Scepter");
        AssertJUnit.assertNotNull(scepter);
        AssertJUnit.assertEquals(1, scepter.getImprintedCards().size());
        AssertJUnit.assertEquals("Dramatic Reversal", scepter.getImprintedCards().get(0).getName());
    }

    @Test
    public void nothingWithoutRocksForScepter() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        addCards("Island", 6, p);           // Reversal doesn't untap lands: no loop
        addCard("Aetherflux Reservoir", p);
        addCardToZone("Isochron Scepter", p, ZoneType.Hand);
        addCardToZone("Dramatic Reversal", p, ZoneType.Hand);

        AssertJUnit.assertNull(planner(p).nextStep());
    }

    @Test
    public void winsWithScepterReversalAetherflux() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        Player opp = game.getPlayers().get(0);
        addCards("Island", 4, p);           // Scepter {2} + first activation {2}
        addCard("Sol Ring", p);             // 2 mana per untap = activation cost
        addCard("Aetherflux Reservoir", p);
        addCardToZone("Isochron Scepter", p, ZoneType.Hand);
        addCardToZone("Dramatic Reversal", p, ZoneType.Hand);
        addCardToZone("Brainstorm", p, ZoneType.Hand);

        playUntilGameOverOrPhase(game, PhaseType.END_OF_TURN, 3000);

        AssertJUnit.assertTrue("game should be over", game.isGameOver());
        AssertJUnit.assertTrue("opponent should have lost", opp.hasLost());
        AssertJUnit.assertTrue("p should have won", p.hasWon());
        // Scepter + 7 copies: 20 + 1 + (2 + ... + 8) = 56, the first total above 50; the shot leaves 6
        AssertJUnit.assertEquals("shot as soon as life passed 50", 6, p.getLife());
    }

    /** Breach on the battlefield, LED and Brain Freeze in hand, {@code junk} bears in our graveyard as escape fuel. */
    private void setupBreach(Player p, int junk) {
        addCards("Island", 3, p);
        addCard("Underworld Breach", p);
        addCardToZone("Lion's Eye Diamond", p, ZoneType.Hand);
        addCardToZone("Brain Freeze", p, ZoneType.Hand);
        for (int i = 0; i < junk; i++) {
            addCardToZone("Runeclaw Bear", p, ZoneType.Graveyard);
        }
        p.getGame().getAction().checkStateEffects(true);
    }

    @Test
    public void winsWithBreachLedBrainFreeze() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        Player opp = game.getPlayers().get(0);
        fillLibrary(opp, 60);
        setupBreach(p, 15);

        playUntilGameOverOrPhase(game, PhaseType.END_OF_TURN, 3000);
        AssertJUnit.assertEquals("opponent milled out this turn", 0, opp.getCardsIn(ZoneType.Library).size());
        AssertJUnit.assertFalse("we didn't mill ourselves out", p.getCardsIn(ZoneType.Library).isEmpty());

        playUntilNextTurn(game);
        playUntilGameOverOrPhase(game, PhaseType.MAIN1, 500);   // opponent draws from an empty library

        AssertJUnit.assertTrue("game should be over", game.isGameOver());
        AssertJUnit.assertTrue("opponent should have lost", opp.hasLost());
        AssertJUnit.assertTrue("p should have won", p.hasWon());
    }

    @Test
    public void breachEscapeNeverExilesLinePieces() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        Player opp = game.getPlayers().get(0);
        fillLibrary(opp, 6);                // one Brain Freeze with its storm copy mills it out
        addCard("Underworld Breach", p);
        addCardToZone("Lion's Eye Diamond", p, ZoneType.Graveyard);
        addCardToZone("Brain Freeze", p, ZoneType.Graveyard);
        for (int i = 0; i < 6; i++) {       // power 2: the stock exile choice takes LED and Brain Freeze (power 0) first
            addCardToZone("Runeclaw Bear", p, ZoneType.Graveyard);
        }
        game.getAction().checkStateEffects(true);

        playUntilGameOverOrPhase(game, PhaseType.END_OF_TURN, 1000);

        AssertJUnit.assertEquals(0, countCardsWithName(game, "Lion's Eye Diamond", ZoneType.Exile));
        AssertJUnit.assertEquals(0, countCardsWithName(game, "Brain Freeze", ZoneType.Exile));
        AssertJUnit.assertEquals("both escapes paid with bears", 6, countCardsWithName(game, "Runeclaw Bear", ZoneType.Exile));
        AssertJUnit.assertEquals(0, opp.getCardsIn(ZoneType.Library).size());
    }

    @Test
    public void nothingWhenBreachLoopRunsOutOfFuel() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        ((LobbyPlayerAi) p.getLobbyPlayer()).setAiProfile("cEDH");   // empty library: no self-mill to refuel
        fillLibrary(game.getPlayers().get(0), 60);
        setupBreach(p, 6);                  // two iterations' worth of fuel, far short of 60 cards

        AssertJUnit.assertNull(planner(p).nextStep());
    }

    @Test
    public void breachLoopMillsItselfForOracle() {
        Game game = initAndCreateGame();
        Player p = setup(game);             // 20 cards: milled to Oracle's devotion of 2
        Player opp = game.getPlayers().get(0);
        fillLibrary(opp, 60);
        addCardToZone(ComboLibrary.ORACLE, p, ZoneType.Hand);   // discarded to LED, then escaped
        setupBreach(p, 15);

        playUntilGameOverOrPhase(game, PhaseType.END_OF_TURN, 3000);

        AssertJUnit.assertTrue("game should be over", game.isGameOver());
        AssertJUnit.assertTrue("p should have won", p.hasWon());
        AssertJUnit.assertEquals("won with Oracle", 1, countCardsWithName(game, ComboLibrary.ORACLE));
        AssertJUnit.assertFalse("opponent not milled out", opp.getCardsIn(ZoneType.Library).isEmpty());
    }

    /** Kinnan, Enduring Vitality and Knacksaw Clique on the battlefield; each opponent has {@code oppLibrary} cards. */
    private Card setupKnacksaw(Player p, int oppLibrary) {
        for (Player opp : p.getOpponents()) {
            fillLibrary(opp, oppLibrary);
        }
        addCard("Kinnan, Bonder Prodigy", p).setSickness(false);
        addCard("Enduring Vitality", p).setSickness(false);
        Card clique = addCard("Knacksaw Clique", p);
        p.getGame().getAction().checkStateEffects(true);
        return clique;
    }

    /** Four players, o1, p, o2, o3 in turn order, in p's (players.get(1)) MAIN1. */
    private Game initAndCreateFourPlayerGame() {
        initAndCreateGame();    // as AITest's three-player game: the Localizer must be loaded first
        List<RegisteredPlayer> players = new ArrayList<>();
        Deck deck = new Deck();
        for (String name : List.of("o1", "p", "o2", "o3")) {
            players.add(new RegisteredPlayer(deck).setPlayer(new LobbyPlayerAi(name, null)));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        Game game = new Game(players, rules, new Match(rules, players, "Test"));
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(1));
        game.getPhaseHandler().onStackResolved();
        return game;
    }

    @Test
    public void knacksawCliqueKillsEveryOpponentBeforeOurNextTurn() {
        Game game = initAndCreateFourPlayerGame();
        Player p = setup(game);
        setupKnacksaw(p, 60).setSickness(false);
        Player o1 = game.getPlayers().get(0);
        Player o2 = game.getPlayers().get(2);
        Player o3 = game.getPlayers().get(3);
        int turn = game.getPhaseHandler().getTurn();
        long start = System.nanoTime();

        playUntilGameOverOrPhase(game, PhaseType.END_OF_TURN, 3000);
        AssertJUnit.assertEquals("the opponent who draws next is exiled", 0, o2.getCardsIn(ZoneType.Library).size());
        AssertJUnit.assertEquals("o3 waits for o2's turn", 60, o3.getCardsIn(ZoneType.Library).size());
        AssertJUnit.assertEquals("o1 waits for o3's turn", 60, o1.getCardsIn(ZoneType.Library).size());

        for (int i = 0; i < 20000 && !game.isGameOver(); i++) {
            game.getPhaseHandler().mainLoopStep();
        }
        long millis = (System.nanoTime() - start) / 1_000_000;

        AssertJUnit.assertTrue("p should have won", p.hasWon());
        AssertJUnit.assertTrue(o1.hasLost() && o2.hasLost() && o3.hasLost());
        AssertJUnit.assertEquals("o1 lost on its draw, before our next turn", turn + 3, game.getPhaseHandler().getTurn());
        // emptying all three libraries in one turn took minutes: every exiled card's may-play effect costs each
        // state check and each AI priority
        AssertJUnit.assertTrue("kill took " + millis + " ms", millis < 60_000);
    }

    @Test
    public void summoningSickKnacksawNeedsShangChi() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        Card clique = setupKnacksaw(p, 30);   // sick: can't tap or untap

        AssertJUnit.assertNull(planner(p).nextStep());

        addCard("Shang-Chi, Master of Kung Fu", p);   // its abilities-as-though-haste lets the sick Clique loop
        game.getAction().checkStateEffects(true);
        SpellAbility sa = planner(p).nextStep();

        AssertJUnit.assertNotNull(sa);
        AssertJUnit.assertEquals(clique, sa.getHostCard());
        AssertJUnit.assertTrue(sa.isManaAbility());
    }

    @Test
    public void deadeyeNavigatorPairsWithShangChi() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        Card shangChi = addCard("Shang-Chi, Master of Kung Fu", p);
        addCard("Colossal Dreadmaw", p);    // the stock pairing choice: the best creature
        game.getAction().checkStateEffects(true);

        Card deadeye = game.getAction().moveToPlay(addCardToZone("Deadeye Navigator", p, ZoneType.Hand), null,
                AbilityKey.newMap());
        playUntilStackClear(game);

        AssertJUnit.assertEquals(shangChi, deadeye.getPairedWith());
    }

    @Test
    public void deadeyeShangChiEngineDigsKnacksawPiecesAndExilesLibrary() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        ((LobbyPlayerAi) p.getLobbyPlayer()).setAiProfile("cEDH");
        Player opp = game.getPlayers().get(0);
        fillLibrary(opp, 20);
        // a five-card library: the first dig takes Knacksaw Clique, the rest go to the bottom, the second finds Vitality
        addCardToZone("Knacksaw Clique", p, ZoneType.Library);
        addCardToZone("Enduring Vitality", p, ZoneType.Library);
        fillLibrary(p, 3);
        addCard("Kinnan, Bonder Prodigy", p).setSickness(false);
        Card shangChi = addCard("Shang-Chi, Master of Kung Fu", p);
        Card deadeye = addCard("Deadeye Navigator", p);
        shangChi.setPairedWith(deadeye);
        deadeye.setPairedWith(shangChi);
        game.getAction().checkStateEffects(true);

        playUntilGameOverOrPhase(game, PhaseType.END_OF_TURN, 3000);
        AssertJUnit.assertEquals(1, countCardsWithName(game, "Knacksaw Clique"));
        AssertJUnit.assertEquals(1, countCardsWithName(game, "Enduring Vitality"));
        AssertJUnit.assertEquals("opponent's library exiled this turn", 0, opp.getCardsIn(ZoneType.Library).size());
    }

    @Test
    public void deadeyeGreatOakEngineDigsKnacksawClique() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        ((LobbyPlayerAi) p.getLobbyPlayer()).setAiProfile("cEDH");
        Player opp = game.getPlayers().get(0);
        fillLibrary(opp, 20);
        addCardToZone("Knacksaw Clique", p, ZoneType.Library);
        fillLibrary(p, 4);
        addCard("Kinnan, Bonder Prodigy", p).setSickness(false);
        addCard("Enduring Vitality", p).setSickness(false);   // every creature taps for any colour
        addCard("Llanowar Elves", p).setSickness(false);
        Card greatOak = addCard("Great Oak Guardian", p);
        Card deadeye = addCard("Deadeye Navigator", p);
        deadeye.setSickness(false);
        greatOak.setPairedWith(deadeye);
        deadeye.setPairedWith(greatOak);
        game.getAction().checkStateEffects(true);

        playUntilGameOverOrPhase(game, PhaseType.END_OF_TURN, 3000);

        AssertJUnit.assertEquals("Kinnan dug up Knacksaw Clique", 1, countCardsWithName(game, "Knacksaw Clique"));
        AssertJUnit.assertNotSame("Great Oak was flickered", greatOak, findCardWithName(game, "Great Oak Guardian"));
        AssertJUnit.assertTrue("Great Oak's trigger pumped our creatures",
                findCardWithName(game, "Llanowar Elves").getNetPower() >= 3);
    }

    @Test
    public void kittenFlickersTeferiForAnotherMinusThree() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        addCardToZone(ComboLibrary.ORACLE, p, ZoneType.Library);
        addCards("Island", 2, p);
        addCard("Swamp", p);
        Card tivit = addCard("Tivit, Seller of Secrets", p);   // has an enter trigger: the stock flicker target
        addCard("Displacer Kitten", p);
        Card teferi = addCard("Teferi, Time Raveler", p);
        teferi.setCounters(CounterEnumType.LOYALTY, 1);         // spent: only a flicker gives it another -3
        addCardToZone("Sol Ring", p, ZoneType.Hand);
        game.getAction().checkStateEffects(true);

        SpellAbility first = planner(p).nextStep();
        AssertJUnit.assertNotNull(first);
        AssertJUnit.assertEquals("Sol Ring", first.getHostCard().getName());

        for (int i = 0; i < 200 && findCardWithName(game, "Teferi, Time Raveler") == teferi; i++) {
            game.getPhaseHandler().mainLoopStep();
        }

        Card flickered = findCardWithName(game, "Teferi, Time Raveler");
        AssertJUnit.assertNotNull(flickered);
        AssertJUnit.assertNotSame("Kitten flickered Teferi", teferi, flickered);
        AssertJUnit.assertEquals("fresh loyalty", 4, flickered.getCounters(CounterEnumType.LOYALTY));
        AssertJUnit.assertEquals("fresh activation", 0, flickered.getPlaneswalkerAbilityActivated());
        AssertJUnit.assertSame("Tivit not flickered", tivit, findCardWithName(game, "Tivit, Seller of Secrets"));
        AssertJUnit.assertEquals("no vote tokens", 0, CardLists.count(p.getCardsIn(ZoneType.Battlefield), Card::isToken));
    }

    /**
     * Displacer Kitten, Teferi and Sol Ring on the battlefield, two Islands and a Swamp; Oracle in {@code oracleZone},
     * then 10 bears and Consultation under the 20 bears.
     */
    private void setupKittenTeferi(Player p, ZoneType oracleZone) {
        addCardToZone(ComboLibrary.ORACLE, p, oracleZone);
        fillLibrary(p, 10);
        addCardToZone(ComboLibrary.CONSULTATION, p, ZoneType.Library);
        addCards("Island", 2, p);
        addCard("Swamp", p);
        addCard("Displacer Kitten", p);
        // addCard skips the enter-with-loyalty replacement
        addCard("Teferi, Time Raveler", p).setCounters(CounterEnumType.LOYALTY, 4);
        addCard("Sol Ring", p);
        p.getGame().getAction().checkStateEffects(true);
    }

    @Test
    public void kittenTeferiDrawsIntoOracleAndWins() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        Player opp = game.getPlayers().get(0);
        setupKittenTeferi(p, ZoneType.Library);   // Oracle is the 21st card

        playUntilGameOverOrPhase(game, PhaseType.END_OF_TURN, 5000);

        AssertJUnit.assertTrue("game should be over", game.isGameOver());
        AssertJUnit.assertTrue("opponent should have lost", opp.hasLost());
        AssertJUnit.assertTrue("p should have won", p.hasWon());
    }

    @Test
    public void nothingWhenOracleIsGone() {
        Game game = initAndCreateGame();
        Player p = setup(game);
        setupKittenTeferi(p, ZoneType.Exile);

        AssertJUnit.assertNull(planner(p).nextStep());
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
