package forge.ai.cedh;

import java.util.Set;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * A card a line needs, and the zones of ours it may be in for the line to count as assembled. In exile it only
 * counts while imprinted on one of our permanents.
 */
public record ComboPiece(String card, Set<ZoneType> zones) {
    /** Thoracle lines: Oracle already on the battlefield must not satisfy them. */
    static ComboPiece inHand(String card) {
        return new ComboPiece(card, Set.of(ZoneType.Hand));
    }

    static ComboPiece inHandOrPlay(String card) {
        return new ComboPiece(card, Set.of(ZoneType.Hand, ZoneType.Battlefield));
    }

    /** In hand (to be imprinted), or already imprinted on our permanent (Reversal under Isochron Scepter). */
    static ComboPiece imprintable(String card) {
        return new ComboPiece(card, Set.of(ZoneType.Hand, ZoneType.Exile));
    }

    static ComboPiece onBattlefield(String card) {
        return new ComboPiece(card, Set.of(ZoneType.Battlefield));
    }

    /** Our copy of the piece in one of its zones, or null. */
    Card find(Player p) {
        for (ZoneType zone : zones) {
            for (Card c : p.getCardsIn(zone)) {
                if (c.getName().equals(card) && (zone != ZoneType.Exile || imprintedOnOurs(p, c))) {
                    return c;
                }
            }
        }
        return null;
    }

    private static boolean imprintedOnOurs(Player p, Card exiled) {
        for (Card host : p.getCardsIn(ZoneType.Battlefield)) {
            if (host.hasImprintedCard(exiled)) {
                return true;
            }
        }
        return false;
    }
}
