package flavor.pie.bungeelib;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import org.spongepowered.api.GameState;
import org.spongepowered.api.Platform;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.game.state.GameStartedServerEvent;
import org.spongepowered.api.network.ChannelBinding;
import org.spongepowered.api.network.ChannelBuf;
import org.spongepowered.api.network.RawDataListener;
import org.spongepowered.api.network.RemoteConnection;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.serializer.TextSerializers;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * The library class for BungeeLib.
 *
 * @author Adam Spofford
 */
public class BungeeLib {
    private Object plugin;
    private ChannelBinding.RawDataChannel chan;
    private ChannelListener listener;

    /**
     * Constructs a new library instance for your plugin.
     *
     * @param container The plugin this is for
     */
    public BungeeLib(PluginContainer container) {
        checkNotNull(container, "container");
        plugin = container.getInstance().get();
        Sponge.getEventManager().registerListener(container.getInstance().get(), GameStartedServerEvent.class, event -> {
            chan = Sponge.getChannelRegistrar().createRawChannel(plugin, "BungeeCord");
            listener = new ChannelListener();
            chan.addListener(Platform.Type.SERVER, listener);
        });
    }

    private void checkState() throws IllegalStateException {
        if (!Sponge.getGame().getState().equals(GameState.SERVER_STARTED)) {
            throw new IllegalStateException("Server has not started!");
        }
    }

    private Player getPlayer() throws IllegalStateException {
        try {
            return Sponge.getServer().getOnlinePlayers().iterator().next();
        } catch (NoSuchElementException ex) {
            throw new NoPlayerOnlineException();
        }
    }

    private ChannelBuf getChannelBuf() {
        try {
            return (ChannelBuf) Class.forName("org.spongepowered.common.network.SpongeNetworkManager")
                    .getMethod("toChannelBuf", Class.forName("io.netty.buffer.ByteBuf"))
                    .invoke(null, Class.forName("io.netty.buffer.Unpooled").getMethod("buffer").invoke(null));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Connects a player on this server to another server.
     *
     * @param p The player to connect
     * @param server The server to send them to
     * @throws IllegalStateException If the {@link GameState} is not
     * {@link GameState#SERVER_STARTED}
     */
    public void connectPlayer(Player p, String server) {
        checkState();
        checkNotNull(p, "p");
        checkNotNull(server, "server");
        chan.sendTo(p, buf -> buf.writeUTF("Connect").writeUTF(server));
    }

    /**
     * Connects a player from any server to any other server.
     *
     * @param player The player to connect
     * @param server The server to send them to
     * @throws IllegalStateException If the {@link GameState} is not
     * {@link GameState#SERVER_STARTED}
     * @throws NoPlayerOnlineException If there is no player online
     */
    public void connectPlayer(String player, String server) {
        checkState();
        checkNotNull(player, "player");
        checkNotNull(server, "server");
        chan.sendTo(getPlayer(), buf -> buf.writeUTF("ConnectOther").writeUTF(player).writeUTF(server));
    }

    /**
     * Gets the real {@link InetSocketAddress} of a player.
     *
     * <p>Do not call the returned {@link CompletableFuture} immediately, or
     * the entire server will hang.</p>
     *
     * @param player The player to get the address of
     * @return A future which returns the address.
     * @throws IllegalStateException If the {@link GameState} is not
     * {@link GameState#SERVER_STARTED}
     */
    public CompletableFuture<InetSocketAddress> getPlayerIP(Player player) {
        checkState();
        checkNotNull(player, "player");
        chan.sendTo(player, buf -> buf.writeUTF("IP"));
        CompletableFuture<InetSocketAddress> addr = new CompletableFuture<>();
        listener.map.put(
                (buf, conn) -> buf.resetRead().readUTF().equals("IP") && conn.equals(player),
                buf -> addr.complete(new InetSocketAddress(buf.readUTF(), buf.readInteger()))
        );
        return addr;
    }

    /**
     * Gets the number of players on a given server.
     *
     * <p>Do not call the returned {@link CompletableFuture} immediately, or
     * the entire server will hang.</p>
     *
     * @param server The server to query
     * @return A future which returns the count
     * @throws IllegalStateException If the {@link GameState} is not
     * {@link GameState#SERVER_STARTED}
     * @throws NoPlayerOnlineException If there is no player online
     */
    public CompletableFuture<Integer> getPlayerCount(String server) {
        checkState();
        checkNotNull(server, "server");
        chan.sendTo(getPlayer(), buf -> buf.writeUTF("PlayerCount").writeUTF(server));
        CompletableFuture<Integer> count = new CompletableFuture<>();
        listener.map.put(
                (buf, conn) -> buf.resetRead().readUTF().equals("PlayerCount") && buf.readUTF().equals(server),
                buf -> count.complete(buf.readInteger())
        );
        return count;
    }

    /**
     * Gets the number of players on all servers.
     *
     * <p>Do not call the returned {@link CompletableFuture} immediately, or
     * the entire server will hang.</p>
     *
     * @return A future which returns the count
     * @throws IllegalStateException If the {@link GameState} is not
     * {@link GameState#SERVER_STARTED}
     * @throws NoPlayerOnlineException If there is no player online
     */
    public CompletableFuture<Integer> getGlobalPlayerCount() {
        return getPlayerCount("ALL");
    }

    /**
     * Gets all online players on a specific server.
     *
     * <p>Do not call the returned {@link CompletableFuture} immediately, or
     * the entire server will hang.</p>
     *
     * @param server The server to query
     * @return A future which returns the players
     * @throws IllegalStateException If the {@link GameState} is not
     * {@link GameState#SERVER_STARTED}
     * @throws NoPlayerOnlineException If there is no player online
     */
    public CompletableFuture<List<String>> getOnlinePlayers(String server) {
        checkState();
        checkNotNull(server, "server");
        chan.sendTo(getPlayer(), buf -> buf.writeUTF("PlayerList").writeUTF(server));
        CompletableFuture<List<String>> list = new CompletableFuture<>();
        listener.map.put(
                (buf, conn) -> buf.resetRead().readUTF().equals("PlayerList") && buf.readUTF().equals(server),
                buf -> list.complete(ImmutableList.copyOf(buf.readUTF().split(", ")))
        );
        return list;
    }

    /**
     * Gets a list of every single player online.
     *
     * <p>Do not call the returned {@link CompletableFuture} immediately, or
     * the entire server will hang.</p>
     *
     * @return A future which returns the players
     * @throws IllegalStateException If the {@link GameState} is not
     * {@link GameState#SERVER_STARTED}
     * @throws NoPlayerOnlineException If there is no player online
     */
    public CompletableFuture<List<String>> getAllOnlinePlayers() {
        return getOnlinePlayers("ALL");
    }

    /**
     * Gets a list of all servers on the network.
     *
     * <p>Do not call the returned {@link CompletableFuture} immediately, or
     * the entire server will hang.</p>
     *
     * @return A future which returns the servers
     * @throws IllegalStateException If the {@link GameState} is not
     * {@link GameState#SERVER_STARTED}
     * @throws NoPlayerOnlineException If there is no player online
     */
    public CompletableFuture<List<String>> getServerList() {
        checkState();
        CompletableFuture<List<String>> list = new CompletableFuture<>();
        chan.sendTo(getPlayer(), buf -> buf.writeUTF("GetServers"));
        listener.map.put(
                (buf, conn) -> buf.resetRead().readUTF().equals("GetServers"),
                buf -> list.complete(ImmutableList.copyOf(buf.readUTF().split(", ")))
        );
        return list;
    }

    /**
     * Sends a message to a player elsewhere on the network.
     *
     * @param player The player to send the message to
     * @param message The message to send
     * @throws IllegalStateException If the {@link GameState} is not
     * {@link GameState#SERVER_STARTED}
     */
    @SuppressWarnings("deprecated")
    public void sendMessage(String player, Text message) {
        checkState();
        checkNotNull(player, "player");
        checkNotNull(message, "message");
        chan.sendTo(getPlayer(), buf -> buf.writeUTF("Message").writeUTF(player)
                .writeUTF(TextSerializers.LEGACY_FORMATTING_CODE.serialize(message)));
    }

    /**
     * Gets the the Bungee-defined name of this server.
     *
     * <p>Do not call the returned {@link CompletableFuture} immediately, or
     * the entire server will hang.</p>
     *
     * @return A future which returns the name
     * @throws IllegalStateException If the {@link GameState} is not
     * {@link GameState#SERVER_STARTED}
     * @throws NoPlayerOnlineException If there is no player online
     */
    public CompletableFuture<String> getServerName() {
        checkState();
        chan.sendTo(getPlayer(), buf -> buf.writeUTF("GetServer"));
        CompletableFuture<String> message = new CompletableFuture<>();
        listener.map.put(
                (buf, conn) -> buf.resetRead().readUTF().equals("GetServer"),
                buf -> message.complete(buf.readUTF())
        );
        return message;
    }

    /**
     * Sends a plugin message to another server.
     *
     * @param payload A consumer to write the data to
     * @param channel The channel to send on
     * @param server The server to send to
     * @throws IllegalStateException If the {@link GameState} is not
     * {@link GameState#SERVER_STARTED}
     * @throws NoPlayerOnlineException If there is no player online
     */
    public void sendServerPluginMessage(Consumer<ChannelBuf> payload, String channel, String server) {
        checkState();
        checkNotNull(payload, "payload");
        checkNotNull(channel, "channel");
        checkNotNull(server, "server");
        ChannelBuf buffer = getChannelBuf();
        payload.accept(buffer);
        buffer.resetRead();
        chan.sendTo(getPlayer(), buf -> buf.writeUTF("Forward").writeUTF(server).writeUTF(channel).writeByteArray(buffer.array()));
    }

    /**
     * Sends a plugin message to all servers on the network.
     *
     * @param payload A consumer to write the data to
     * @param channel The channel to send on
     * @throws IllegalStateException If the {@link GameState} is not
     * {@link GameState#SERVER_STARTED}
     * @throws NoPlayerOnlineException If there is no player online
     */
    public void sendGlobalPluginMessage(Consumer<ChannelBuf> payload, String channel) {
        sendServerPluginMessage(payload, channel, "ALL");
    }

    /**
     * Sends a plugin message to another player on the network.
     *
     * @param payload A consumer to write the data to
     * @param channel The channel to send on
     * @param player The player to send to
     * @throws IllegalStateException If the {@link GameState} is not
     * {@link GameState#SERVER_STARTED}
     * @throws NoPlayerOnlineException If there is no player online
     */
    public void sendPlayerPluginMessage(Consumer<ChannelBuf> payload, String channel, String player) {
        checkState();
        checkNotNull(payload, "payload");
        checkNotNull(channel, "channel");
        checkNotNull(player, "player");
        ChannelBuf buffer = getChannelBuf();
        payload.accept(buffer);
        buffer.resetRead();
        chan.sendTo(getPlayer(), buf -> buf.writeUTF("ForwardToPlayer").writeUTF(player).writeUTF(channel).writeByteArray(buffer.array()));
    }

    /**
     * Gets the real {@link UUID} of a player on this server.
     *
     * <p>Do not call the returned {@link CompletableFuture} immediately, or
     * the entire server will hang.</p>
     *
     * @param player The player to query
     * @return A future which returns the {@link UUID}
     * @throws IllegalStateException If the {@link GameState} is not
     * {@link GameState#SERVER_STARTED}
     */
    public CompletableFuture<UUID> getRealUUID(Player player) {
        checkState();
        checkNotNull(player, "player");
        chan.sendTo(player, buf -> buf.writeUTF("UUID"));
        CompletableFuture<UUID> id = new CompletableFuture<>();
        listener.map.put(
                (buf, conn) -> buf.resetRead().readUTF().equals("UUID") && conn.equals(player),
                buf -> id.complete(UUID.fromString(buf.readUTF()))
        );
        return id;
    }

    /**
     * Gets the real {@link UUID} of a player on the network.
     *
     * <p>Do not call the returned {@link CompletableFuture} immediately, or
     * the entire server will hang.</p>
     *
     * @param player The player to query
     * @return A future which returns the {@link UUID}
     * @throws IllegalStateException If the {@link GameState} is not
     * {@link GameState#SERVER_STARTED}
     * @throws NoPlayerOnlineException If there is no player online
     */
    public CompletableFuture<UUID> getRealUUID(String player) {
        checkState();
        checkNotNull(player, "player");
        chan.sendTo(getPlayer(), buf -> buf.writeUTF("UUIDOther").writeUTF(player));
        CompletableFuture<UUID> id = new CompletableFuture<>();
        listener.map.put(
                (buf, conn) -> buf.resetRead().readUTF().equals("UUIDOther") && buf.readUTF().equals(player),
                buf -> id.complete(UUID.fromString(buf.readUTF()))
        );
        return id;
    }

    /**
     * Gets the {@link InetSocketAddress} of a server on the network.
     *
     * <p>Do not call the returned {@link CompletableFuture} immediately, or
     * the entire server will hang.</p>
     *
     * @param server The server to query
     * @return A future which returns the address
     * @throws IllegalStateException If the {@link GameState} is not
     * {@link GameState#SERVER_STARTED}
     * @throws NoPlayerOnlineException If there is no player online
     */
    public CompletableFuture<InetSocketAddress> getServerIP(String server) {
        checkState();
        checkNotNull(server, "server");
        chan.sendTo(getPlayer(), buf -> buf.writeUTF("ServerIP").writeUTF(server));
        CompletableFuture<InetSocketAddress> address = new CompletableFuture<>();
        listener.map.put(
                (buf, conn) -> buf.resetRead().readUTF().equals("ServerIP") && buf.readUTF().equals(server),
                buf -> address.complete(new InetSocketAddress(buf.readUTF(), buf.readShort()))
        );
        return address;
    }

    /**
     * Kicks a player from whatever server they are on.
     *
     * @param player The player to kick
     * @param reason Why they're being kicked
     * @throws IllegalStateException If the {@link GameState} is not
     * {@link GameState#SERVER_STARTED}
     * @throws NoPlayerOnlineException If there is no player online
     */
    public void kickPlayer(String player, Text reason) {
        checkState();
        checkNotNull(player, "player");
        checkNotNull(reason, "reason");
        chan.sendTo(getPlayer(), buf -> buf.writeUTF("KickPlayer").writeUTF(player).writeUTF(TextSerializers.LEGACY_FORMATTING_CODE.serialize(reason)));
    }

    private static class ChannelListener implements RawDataListener {
        Map<BiPredicate<ChannelBuf, RemoteConnection>, Consumer<ChannelBuf>> map = Maps.newConcurrentMap();

        @Override
        public void handlePayload(ChannelBuf data, RemoteConnection connection, Platform.Type side) {
            map.keySet().stream().filter(pred -> pred.test(data, connection)).findFirst().ifPresent(pred -> {
                map.get(pred).accept(data);
                map.remove(pred);
            });
        }
    }

    /**
     * This API operates via plugin message packets, and therefore requires
     * there to be at least one player on the server.
     */
    public static class NoPlayerOnlineException extends IllegalStateException {}

}
