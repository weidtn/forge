package forge.ai.cedh;

import java.util.Set;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** A card a line needs, and the zones of ours it may be in for the line to count as assembled. */
public record ComboPiece(String card, Set<ZoneType> zones) {
    /** Thoracle lines: Oracle already on the battlefield must not satisfy them. */
    static ComboPiece inHand(String card) {
        return new ComboPiece(card, Set.of(ZoneType.Hand));
    }

    static ComboPiece inHandOrPlay(String card) {
        return new ComboPiece(card, Set.of(ZoneType.Hand, ZoneType.Battlefield));
    }

    /** Our copy of the piece in one of its zones, or null. */
    Card find(Player p) {
        for (ZoneType zone : zones) {
            Card c = ComboPlanner.findByName(p.getCardsIn(zone), card);
            if (c != null) {
                return c;
            }
        }
        return null;
    }
}
