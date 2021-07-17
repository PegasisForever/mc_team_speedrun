package site.pegasis.mc.team_speedrun

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.GameRule
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Arrow
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scoreboard.Team
import java.util.*
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

open class MCTeamSpeedRun : JavaPlugin(), Listener {
    val playerCurrentCompassTargetPlayer = hashMapOf<Player, Player>()
    val onlinePlayers = arrayListOf<Player>()
    private var changeCompassTargetTaskID = -1

    var isStarted = false
        set(value) {
            if (value) {
                val changeCompassTarget = {
                    Bukkit.getOnlinePlayers().forEach { player ->
                        val playerTeam = player.team
                        val currentTarget = playerCurrentCompassTargetPlayer[player]?.location
                        if (playerTeam != null && currentTarget != null) {
                            player.compassTarget = currentTarget
                        }
                    }
                }
                changeCompassTargetTaskID = server.scheduler.scheduleSyncRepeatingTask(this, changeCompassTarget, 0L, 1L)
            } else {
                server.scheduler.cancelTask(changeCompassTargetTaskID)
                changeCompassTargetTaskID = -1
                playerCurrentCompassTargetPlayer.clear()
            }
            field = value
        }

    fun nextCompassTarget(player: Player) {
        if (player.team == null) return
        val currentTargetPlayer = playerCurrentCompassTargetPlayer[player]
        val rotateOffset = if (currentTargetPlayer == null) {
            0
        } else {
            val index = server.onlinePlayers.indexOf(currentTargetPlayer)
            if (index == -1) {
                0
            } else {
                -(index + 1)
            }
        }

        val players = onlinePlayers.clone() as List<Player>
        Collections.rotate(players, rotateOffset)

        val newTargetPlayer = players.find { it.team != null && it.team != player.team }
        if (newTargetPlayer == null) {
            playerCurrentCompassTargetPlayer.remove(player)
        } else {
            playerCurrentCompassTargetPlayer[player] = newTargetPlayer
            player.sendActionBar("Now tracking: ${newTargetPlayer.team!!.color}${newTargetPlayer.name}")
        }
    }

    override fun onEnable() {
        onlinePlayers.addAll(server.onlinePlayers)
        server.pluginManager.registerEvents(PlayerJoinLeaveListener(this), this)
        server.pluginManager.registerEvents(CompassListener(this), this)
        server.pluginManager.registerEvents(AttackListener(this), this)
        server.pluginManager.registerEvents(DeathListener(this), this)
        onlinePlayers.forEach { player ->
            player.reset(isStarted)
        }
        server.worlds.forEach {
            it.setGameRule(GameRule.DO_WEATHER_CYCLE, false)
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (command.name == "start-speedrun") {
            if (isStarted) {
                sender.sendMessage("Speedrun is already started!")
            } else {
                server.worlds.forEach {
                    it.time = 0
                }
                onlinePlayers.forEach { player ->
                    player.reset(true)
                    player.teleport(player.world.spawnLocation)
                    nextCompassTarget(player)
                    player.sendMessage("Speedrun started!")
                }
                isStarted = true
            }
            return true
        } else if (command.name == "end-speedrun") {
            if (!isStarted) {
                sender.sendMessage("Speedrun haven't started!")
            } else {
                isStarted = false
            }
            return true
        } else if (command.name == "resume-speedrun") {
            if (isStarted) {
                sender.sendMessage("Speedrun is already started!")
            } else {
                onlinePlayers.forEach { player ->
                    nextCompassTarget(player)
                    player.sendMessage("Speedrun started!")
                }
                isStarted = true
            }
            return true
        } else if (command.name == "get-compass") {
            if (sender is Player) {
                sender.inventory.addItem(ItemStack(Material.COMPASS))
            } else {
                sender.sendMessage("Only a player can use this command!")
            }
        }
        return false
    }
}

class PlayerJoinLeaveListener(private val plugin: MCTeamSpeedRun) : Listener {
    @EventHandler
    fun onLogin(event: PlayerJoinEvent) {
        plugin.onlinePlayers.add(event.player)

        if (plugin.isStarted) {
            event.player.sendMessage("Speedrun is in progress!")
        } else {
            event.player.reset(false)
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        plugin.onlinePlayers.remove(event.player)
        plugin.playerCurrentCompassTargetPlayer.forEach { (player, targetPlayer) ->
            if (targetPlayer == event.player) {
                plugin.nextCompassTarget(player)
            }
        }
    }

}

class CompassListener(private val plugin: MCTeamSpeedRun) : Listener {
    @EventHandler
    fun onRightClick(event: PlayerInteractEvent) {
        if (plugin.isStarted && event.item?.type == Material.COMPASS) {
            plugin.nextCompassTarget(event.player)
        }
    }

    @EventHandler
    fun onDrop(event: PlayerDropItemEvent) {
        if (plugin.isStarted && event.itemDrop.itemStack.type == Material.COMPASS) {
            event.isCancelled = true
        }
    }
}

class AttackListener(private val plugin: MCTeamSpeedRun) : Listener {
    @EventHandler
    fun onAttack(event: EntityDamageByEntityEvent) {
        if (!plugin.isStarted) {
            val isShoot = event.cause == EntityDamageEvent.DamageCause.PROJECTILE
            val attacker = if (isShoot) {
                (((event.damager as? Arrow)?.shooter) as? Player)
            } else {
                event.damager
            }
            if (attacker is Player) {
                event.isCancelled = true
            }
        }
    }
}

class DeathListener(private val plugin: MCTeamSpeedRun) : Listener {
    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        if (plugin.isStarted) {
            event.setShouldDropExperience(true)
            event.newTotalExp = (event.entity.realExp / 2)
            event.droppedExp = min(event.droppedExp, event.entity.realExp / 3)

            var compass: ItemStack? = null
            val shuffledInventory = event.entity.inventory
                .iterator().asSequence().toList()
                .filter { itemStack ->
                    if (itemStack == null) {
                        false
                    } else if (itemStack.type == Material.COMPASS && compass == null) {
                        compass = itemStack
                        false
                    } else {
                        true
                    }
                }
                .shuffled()
            event.itemsToKeep.clear()
            event.itemsToKeep.addAll(shuffledInventory.subList(0, shuffledInventory.size / 2))
            event.drops.clear()
            event.drops.addAll(shuffledInventory.subList(shuffledInventory.size / 2, shuffledInventory.size))

            if (compass != null) {
                event.itemsToKeep.add(compass)
            }
        }
    }
}

val Player.team: Team?
    get() = this.scoreboard.getEntryTeam(this.name)

fun Player.reset(isGameStarted: Boolean) {
    gameMode = if (isGameStarted) GameMode.SURVIVAL else GameMode.ADVENTURE
    activePotionEffects.forEach { removePotionEffect(it.type) }
    level = 0
    exp = 0f
    foodLevel = 20
    health = getAttribute(Attribute.GENERIC_MAX_HEALTH)!!.value
    for (i in 0..40) {
        inventory.setItem(i, null)
    }
    if (isGameStarted) {
        inventory.setItem(0, ItemStack(Material.COMPASS))
    }
}

fun getExpToLevelUp(level: Int): Int {
    return when {
        level <= 15 -> 2 * level + 7
        level <= 30 -> 5 * level - 38
        else -> 9 * level - 158
    }
}

fun getExpAtLevel(level: Int): Int {
    return when {
        level <= 16 -> (level.toDouble().pow(2.0) + 6 * level).toInt()
        level <= 31 -> (2.5 * level.toDouble().pow(2.0) - 40.5 * level + 360.0).toInt()
        else -> (4.5 * level.toDouble().pow(2.0) - 162.5 * level + 2220.0).toInt()
    }
}

val Player.realExp: Int
    get() {
        return getExpAtLevel(level) + (getExpToLevelUp(level) * exp).roundToInt()
    }
