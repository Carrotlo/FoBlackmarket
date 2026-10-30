package me.foesio.foBlackmarket.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.foesio.foBlackmarket.api.event.BlackmarketPurchaseCompletedEvent;
import me.foesio.foBlackmarket.api.event.BlackmarketPurchaseRequestEvent;
import me.foesio.foBlackmarket.api.event.BlackmarketRestockedEvent;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

final class PublicApiContractTest {

    @Test
    void marketViewDoesNotRetainMutableOfferLists() {
        List<OfferView> source = new ArrayList<>();
        source.add(new OfferView(10, "reward", "Reward", "rare", 3, "$5"));
        MarketView view = new MarketView(100L, 200L, source);
        source.clear();

        assertEquals(1, view.offers().size());
        assertThrows(UnsupportedOperationException.class, () -> view.offers().clear());
    }

    @Test
    void purchaseRequestIsCancellableAndCompletionEventsAreSynchronous() {
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[] {Player.class}, (proxy, method, arguments) -> null);
        PurchaseQuote quote = new PurchaseQuote(PurchaseQuote.Status.AVAILABLE, "reward", 3, "");
        BlackmarketPurchaseRequestEvent request = new BlackmarketPurchaseRequestEvent(player, quote);
        request.setCancelled(true);

        assertTrue(request.isCancelled());
        assertFalse(request.isAsynchronous());
        assertFalse(new BlackmarketPurchaseCompletedEvent(player, "reward", 2).isAsynchronous());
        assertFalse(new BlackmarketRestockedEvent(RestockCause.API, 100L, 200L).isAsynchronous());
    }
}
