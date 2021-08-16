package site.pegasis.mc.team_speedrun

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.node.types.InheritanceNode
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.EnderDragon
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.CompassMeta
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scoreboard.Team
import java.math.RoundingMode
import java.text.DecimalFormat
import java.util.*
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

open class MCTeamSpeedRun : JavaPlugin(), Listener {
    private val playerCurrentCompassTargetPlayer = hashMapOf<Player, Player>()
    private val onlinePlayers = arrayListOf<Player>()
    private var changeCompassTargetTaskID = -1
    private lateinit var lp: LuckPerms

    var isStarted = false
        set(value) {
            if (value) {
                val changeCompassTarget = {
                    Bukkit.getOnlinePlayers().forEach { player ->
                        val currentTargetPlayer = playerCurrentCompassTargetPlayer[player]
                        val currentTarget = currentTargetPlayer?.location
                        if (currentTarget != null) {
                            player.compassTarget = currentTarget
                            player.inventory
                                .filter { it?.type == Material.COMPASS }
                                .forEach { compass ->
                                    compass.itemMeta = (compass.itemMeta as CompassMeta).apply {
                                        lodestone = currentTarget
                                        isLodestoneTracked = false
                                        setDisplayName("${ChatColor.RESET}${ChatColor.WHITE}Compass: Tracking ${currentTargetPlayer.team?.color ?: ChatColor.WHITE}[${currentTargetPlayer.team?.name}] ${currentTargetPlayer.name}")
                                    }
                                }
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

    override fun onEnable() {
        lp = LuckPermsProvider.get()
        onlinePlayers.addAll(server.onlinePlayers)
        server.pluginManager.registerEvents(this, this)
        server.worlds.forEach {
            it.setGameRule(GameRule.DO_WEATHER_CYCLE, false)
            it.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
            it.time = 0
        }
        onlinePlayers.forEach {
            it.gameMode = GameMode.ADVENTURE
            it.allowTP = false
        }
        GlobalScope.launch {
            System.getenv("DISCORD_WEBHOOK")?.let { url ->
                val client = HttpClient(CIO)
                val ip = client
                    .get<HttpResponse>("https://api64.ipify.org/")
                    .receive<String>()

                client.post(url) {
                    this.body = """
                    {
                      "content": "IP: `${ip}`",
                      "embeds": null
                    }"""
                    this.header("Content-Type", "application/json")
                }
            }
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (command.name == "start-speedrun") {
            if (isStarted) {
                sender.sendMessage("Speedrun is already started!")
            } else {
                server.worlds.forEach {
                    it.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true)
                    it.time = 0
                }
                onlinePlayers.forEach { player ->
                    player.reset(true)
                    player.teleport(server.worlds[0].spawnLocation)
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
                sender.sendMessage("Speedrun ended!")
            }
            return true
        } else if (command.name == "resume-speedrun") {
            if (isStarted) {
                sender.sendMessage("Speedrun is already started!")
            } else {
                server.worlds.forEach {
                    it.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true)
                    it.time = 0
                }
                onlinePlayers.forEach { player ->
                    nextCompassTarget(player)
                    player.gameMode = GameMode.SURVIVAL
                    player.sendMessage("Speedrun resumed!")
                }
                isStarted = true
            }
            return true
        } else if (command.name == "reset-player") {
            if (args.size != 1) {
                sender.sendMessage("${ChatColor.RED}Usage: /reset-player <player>")
            } else {
                val player = onlinePlayers.find { it.name == args[0] }
                if (player == null) {
                    sender.sendMessage("${ChatColor.RED}Player \"${args[0]}\" doesn't exist.")
                } else {
                    player.reset(isStarted)
                }
            }
            return true
        } else if (command.name == "get-compass") {
            if (sender is Player) {
                sender.inventory.addItem(getCompassItemStack())
            } else {
                sender.sendMessage("Only a player can use this command!")
            }
            return true
        } else if (command.name == "quit") {
            if (!isStarted) {
                sender.sendMessage("Speedrun is not started yet!")
            } else {
                if (sender is Player) {
                    sender.gameMode = GameMode.SPECTATOR
                    sender.allowTP = true
                } else {
                    sender.sendMessage("Only a player can use this command!")
                }
            }

            return true
        }
        return false
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        onlinePlayers.add(event.player)

        if (isStarted) event.player.sendMessage("Speedrun is in progress!")
        if (event.player.gameMode != GameMode.SPECTATOR) {
            event.player.allowTP = false
            if (isStarted) {
                event.player.sendMessage("Speedrun is in progress!")
                event.player.gameMode = GameMode.SURVIVAL
            } else {
                event.player.gameMode = GameMode.ADVENTURE
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        onlinePlayers.remove(event.player)
        playerCurrentCompassTargetPlayer
            .filter { (_, targetPlayer) -> targetPlayer == event.player }
            .forEach { (player, _) -> nextCompassTarget(player) }
    }

    @EventHandler
    fun onRightClick(event: PlayerInteractEvent) {
        if (isStarted && event.item?.type == Material.COMPASS) {
            nextCompassTarget(event.player)
        }
    }

    @EventHandler
    fun onPlaceBlock(event: BlockPlaceEvent) {
        if (event.blockPlaced.type == Material.COMPOSTER) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onDrop(event: PlayerDropItemEvent) {
        if (isStarted && event.itemDrop.itemStack.type == Material.COMPASS) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onAttack(event: EntityDamageEvent) {
        if (!isStarted && event.attackerPlayer != null) {
            event.isCancelled = true
        } else if (isStarted) {
            val attackerPlayer = event.attackerPlayer
            if (event.entity.type == EntityType.ENDER_CRYSTAL && event.entity.location.world.environment == World.Environment.THE_END) {
                val message = if (attackerPlayer == null) {
                    "${ChatColor.LIGHT_PURPLE}Someone destroyed an ender crystal!"
                } else {
                    "${attackerPlayer.team?.color ?: ChatColor.LIGHT_PURPLE}${attackerPlayer.name}${ChatColor.LIGHT_PURPLE} destroyed an ender crystal!"
                }
                onlinePlayers.forEach { it.sendMessage(message) }
            } else if (event.entity.type == EntityType.ENDER_DRAGON) {
                val df = DecimalFormat("#.##")
                df.roundingMode = RoundingMode.HALF_EVEN
                val dragonHealthPercent = (event.entity as EnderDragon).run { health / getAttribute(Attribute.GENERIC_MAX_HEALTH)!!.value } * 100
                val healthColor = when {
                    dragonHealthPercent > 70 -> ChatColor.GREEN
                    dragonHealthPercent > 35 -> ChatColor.YELLOW
                    else -> ChatColor.RED
                }
                val healthText = "${healthColor}${df.format(dragonHealthPercent)}%${ChatColor.LIGHT_PURPLE}"
                val message = if (attackerPlayer == null) {
                    "Someone is attacking the ender dragon! Dragon health: $healthText"
                } else {
                    "${attackerPlayer.team?.color ?: ChatColor.WHITE}${attackerPlayer.name}${ChatColor.RESET} is attacking the ender dragon! Dragon health: $healthText"
                }
                onlinePlayers.forEach { it.sendMessage(message) }
            }
        }
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        if (isStarted) {
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
            event.itemsToKeep.addAll(shuffledInventory.subList(0, shuffledInventory.size / 4 * 3))
            event.drops.clear()
            event.drops.addAll(shuffledInventory.subList(shuffledInventory.size / 4 * 3, shuffledInventory.size))

            if (compass != null) {
                event.itemsToKeep.add(compass)
            }
        }
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        if (isStarted) {
            fun addOneDrop(type: Material) {
                val itemStack = event.drops.find { it.type == type }
                if (itemStack != null) {
                    itemStack.amount++
                } else {
                    event.drops.add(ItemStack(type, 1))
                }
            }
            if (event.entity.type == EntityType.ENDERMAN) {
                addOneDrop(Material.ENDER_PEARL)
            } else if (event.entity.type == EntityType.BLAZE) {
                addOneDrop(Material.BLAZE_ROD)
            }
        }
    }

    @EventHandler
    fun onBreakGravel(event: BlockBreakEvent) {
        if (isStarted && event.block.type == Material.GRAVEL && event.player.gameMode == GameMode.SURVIVAL) {
            if (Random.nextInt(0, 10) < 2) {
                event.isDropItems = false
                val blockLocation = event.block.location
                blockLocation.world.dropItemNaturally(blockLocation, ItemStack(Material.FLINT))
            }
        }
    }

    private fun nextCompassTarget(player: Player) {
        val currentTargetPlayer = playerCurrentCompassTargetPlayer[player]
        val rotateOffset = if (currentTargetPlayer == null) {
            0
        } else {
            val index = onlinePlayers.indexOfFirst { it.name == currentTargetPlayer.name }
            if (index == -1) {
                0
            } else {
                -(index + 1)
            }
        }

        val players = onlinePlayers.clone() as List<Player>
        Collections.rotate(players, rotateOffset)

        val newTargetPlayer = players.find { it.gameMode == GameMode.SURVIVAL && it.name != player.name }
        if (newTargetPlayer == null) {
            playerCurrentCompassTargetPlayer.remove(player)
        } else {
            playerCurrentCompassTargetPlayer[player] = newTargetPlayer
        }
    }

    private fun getCompassItemStack() = ItemStack(Material.COMPASS).apply {
        addUnsafeEnchantment(Enchantment.DAMAGE_ALL, 1)
        itemMeta = (itemMeta as CompassMeta).apply {
            isLodestoneTracked = false
            addItemFlags(ItemFlag.HIDE_ENCHANTS)
        }
    }

    private val Player.team: Team?
        get() = this.scoreboard.getEntryTeam(this.name)

    private fun Player.reset(isGameStarted: Boolean) {
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
            inventory.setItem(0, getCompassItemStack())
        }
    }

    private fun getExpToLevelUp(level: Int): Int {
        return when {
            level <= 15 -> 2 * level + 7
            level <= 30 -> 5 * level - 38
            else -> 9 * level - 158
        }
    }

    private fun getExpAtLevel(level: Int): Int {
        return when {
            level <= 16 -> (level.toDouble().pow(2.0) + 6 * level).toInt()
            level <= 31 -> (2.5 * level.toDouble().pow(2.0) - 40.5 * level + 360.0).toInt()
            else -> (4.5 * level.toDouble().pow(2.0) - 162.5 * level + 2220.0).toInt()
        }
    }

    private val Player.realExp: Int
        get() {
            return getExpAtLevel(level) + (getExpToLevelUp(level) * exp).roundToInt()
        }

    private var Player.allowTP: Boolean
        get() = TODO()
        set(value) {
            val lpUser = this@MCTeamSpeedRun.lp.userManager.getUser(uniqueId)!!
            if (value) {
                lpUser.data().add(InheritanceNode.builder("spectator").build())
            } else {
                lpUser.data().clear()
                lpUser.primaryGroup = "default"
            }
            this@MCTeamSpeedRun.lp.userManager.saveUser(lpUser)
        }

    private val EntityDamageEvent.attackerPlayer: Player?
        get() {
            return if (this is EntityDamageByEntityEvent) {
                val isShoot = cause == EntityDamageEvent.DamageCause.PROJECTILE
                if (isShoot) {
                    (((damager as? AbstractArrow)?.shooter) as? Player)
                } else {
                    damager as? Player
                }
            } else {
                null
            }
        }
}
