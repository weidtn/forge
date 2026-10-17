package forge.ai.cedh;

import java.util.List;
import java.util.function.Predicate;

import forge.game.player.Player;

/**
 * A known win line. All cards in {@code castOrder} must be in hand; they are cast in that order.
 * {@code condition} is an extra board-state check (e.g. "library is empty").
 */
public record ComboLine(String id, List<String> castOrder, Predicate<Player> condition) {
    public String winCard() {
        return castOrder.get(castOrder.size() - 1);
    }
}
