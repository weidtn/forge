package forge.ai.cedh;

import java.util.List;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;

/**
 * Resolution-time choices of a line, asked while it is the planner's active line. Every default returns null: keep
 * the stock AI's choice.
 */
public interface ComboChoices {
    ComboChoices NONE = new ComboChoices() {};

    /** Targets of our trigger, at most its maximum, none already targeted by our stack items; null: stock. */
    default List<GameEntity> triggerTargets(Player ai, SpellAbility trigger) {
        return null;
    }

    /** The card {@code sa} moves from a hidden zone to {@code destination}, from {@code options}; null: stock. */
    default Card hiddenZoneChoice(Player ai, SpellAbility sa, ZoneType destination, CardCollectionView options) {
        return null;
    }

    /** The one creature {@code sa}'s sacrifice cost takes from {@code options}; null: stock. */
    default Card sacrifice(Player ai, SpellAbility sa, CardCollectionView options) {
        return null;
    }

    /** Whether to pay an optional trigger's cost; null: stock. */
    default Boolean payOptionalTrigger(Player ai, WrappedAbility trigger) {
        return null;
    }

    /** The one colour all mana of {@code sa}'s combination should be; null: stock. */
    default Byte comboManaColor(Player ai, SpellAbility sa) {
        return null;
    }
}
