package dev.pawfect.bloodcamp.dungeon;

import java.util.Set;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

final class Net {
    private static int pingId;

    private Net() {}

    static void tickPings() {
        if (++pingId == 0) pingId = 1;
        ClientboundPingPacket packet = new ClientboundPingPacket(pingId);
        for (Player p : Bukkit.getOnlinePlayers()) ((CraftPlayer) p).getHandle().connection.send(packet);
    }

    static void teleportKeepLook(Player player, double x, double y, double z) {
        ((CraftPlayer) player).getHandle().connection.teleport(new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, 0f, 0f),
            Set.of(Relative.X_ROT, Relative.Y_ROT), PlayerTeleportEvent.TeleportCause.PLUGIN);
    }
}
