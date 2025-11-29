package pers.xanadu.enderdragon.listener;

import com.ericdebouwer.petdragon.api.PetDragonAPI;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import pers.xanadu.enderdragon.config.Config;
import pers.xanadu.enderdragon.config.Lang;
import pers.xanadu.enderdragon.event.DragonRespawnPostEvent;
import pers.xanadu.enderdragon.hook.HookManager;
import pers.xanadu.enderdragon.manager.DamageManager;
import pers.xanadu.enderdragon.manager.DragonManager;
import pers.xanadu.enderdragon.manager.WorldManager;
import pers.xanadu.enderdragon.metadata.DragonInfo;
import pers.xanadu.enderdragon.metadata.MyDragon;
import pers.xanadu.enderdragon.util.Version;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static pers.xanadu.enderdragon.EnderDragon.*;
import static pers.xanadu.enderdragon.manager.DragonManager.*;
import static pers.xanadu.enderdragon.manager.GlowManager.*;

public class DragonSpawnListener implements Listener {
    
    /**
     * Check if the dragon respawn was initiated by the plugin.
     * This is determined by checking if there's a designated dragon (plugin-placed crystals with tags)
     * 
     * @param world The world to check
     * @return true if this is a plugin-initiated respawn
     */
    private boolean isPluginInitiatedRespawn(World world) {
        return DragonManager.getDesignatedDragon(world) != null;
    }
    
    /**
     * Check if this is the first dragon that has ever spawned in this world.
     * Uses DragonBattle.hasBeenPreviouslyKilled() API for 1.16+
     * For older versions, always returns false (treat as not first dragon)
     * 
     * @param world The world to check
     * @return true if this is the first dragon (dragon has never been killed before in this world)
     */
    private boolean isFirstDragonInWorld(World world) {
        if(world.getEnvironment() != World.Environment.THE_END) return false;
        
        // For 1.16+ versions, use DragonBattle API
        if(Version.mcMainVersion >= 16){
            DragonBattle battle = world.getEnderDragonBattle();
            if(battle == null) return false;
            // hasBeenPreviouslyKilled() returns true if dragon was killed before
            // We want to detect FIRST dragon, so we negate it
            return !battle.hasBeenPreviouslyKilled();
        }
        
        // For older versions (1.12-1.15), try to use reflection
        try {
            Object battle = DragonManager.getEnderDragonBattle(world);
            if(battle == null) return false;
            
            // Field 'k' represents whether dragon has been killed (previouslyKilled)
            java.lang.reflect.Field k = battle.getClass().getDeclaredField("k");
            k.setAccessible(true);
            boolean previouslyKilled = (boolean) k.get(battle);
            return !previouslyKilled;
        } catch (Exception ex) {
            if(Config.debug) Lang.warn("Failed to check first dragon status for world " + world.getName() + ": " + ex.getMessage());
            return false; // On error, assume it's not the first dragon
        }
    }
    @EventHandler(priority = EventPriority.LOW)
    public void OnDragonSpawn(final CreatureSpawnEvent e){
        if(!(e.getEntity() instanceof EnderDragon)) return;
        //compatibility with plugin PetDragon
        if (HookManager.isPetDragonInstalled()){
            if (PetDragonAPI.getInstance().isPetDragon(e.getEntity())) return;
        }
        if(!WorldManager.enable_worlds.contains(e.getEntity().getWorld().getName())) return;
        if(Config.blacklist_spawn_reason.contains(e.getSpawnReason().name())) return;
        EnderDragon dragon = (EnderDragon) e.getEntity();
        World world = dragon.getWorld();
        
        // Check if this is the first dragon in this world using DragonBattle API
        boolean isFirstDragon = isFirstDragonInWorld(world);
        
        // Handle first dragon based on configuration (must check before player_crystal check)
        if(isFirstDragon && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.DEFAULT){
            String mode = Config.first_dragon_mode.toUpperCase();
            if(mode.equals("VANILLA")){
                if(Config.debug) Lang.info("First dragon in world " + world.getName() + " spawned - VANILLA mode, ignoring.");
                return;
            }
            else if(mode.equals("COMMANDS")){
                if(Config.debug) Lang.info("First dragon in world " + world.getName() + " spawned - COMMANDS mode, executing commands.");
                // Execute commands after a short delay to ensure dragon and egg are properly spawned
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    for(String cmd : Config.first_dragon_commands){
                        String finalCmd = cmd.replace("%world%", world.getName());
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
                    }
                }, 20L); // Wait 1 second (20 ticks)
                return;
            }
            // DEFAULT mode: continue normal processing
            if(Config.debug) Lang.info("First dragon in world " + world.getName() + " spawned - DEFAULT mode, processing normally.");
        }
        
        // If player_crystal_vanilla_dragon is enabled, ignore dragons spawned by player-placed crystals
        // (This check is after first_dragon to ensure first dragon commands are always executed)
        if(Config.player_crystal_vanilla_dragon && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.DEFAULT){
            // Check if this dragon was spawned by player-placed crystals (not plugin-initiated)
            if(!isPluginInitiatedRespawn(world)){
                if(Config.debug) Lang.info("Dragon in world " + world.getName() + " spawned by player crystals - keeping vanilla behavior.");
                return;
            }
        }
        
        MyDragon myDragon = null;
        if(e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.DEFAULT){
            myDragon = getDesignatedDragon(world);
        }
        if(myDragon == null) myDragon = judge();
        if(myDragon == null) {
            Lang.warn("special_dragon_jude_mode setting error!");
            return;
        }
        DragonInfo info = new DragonInfo(dragon,myDragon.unique_name);
        Bukkit.getPluginManager().callEvent(new DragonRespawnPostEvent(dragon,e.getSpawnReason(),info));
        MyDragon update = getFromInfo(info);
        if(update == null) {
            Lang.error("Unknown unique_name in DragonInfo: "+info.unique_name);
        }
        else{
            myDragon = update;
        }
        {
            existing_dragon.put(dragon.getUniqueId(),new DragonInfo(dragon,myDragon.unique_name));
            DragonInfo old = main_dragon.get(world.getName());
            if(old == null || !old.getHandle().isValid()) main_dragon.put(world.getName(),new DragonInfo(dragon,myDragon.unique_name));
        }
        setSpecialKey(dragon, myDragon.unique_name);
        DamageManager.data.put(dragon.getUniqueId(),new ConcurrentHashMap<>());
        int times = data.getInt("times");
        Lang.runCommands(myDragon.spawn_cmd);
        for(String str : myDragon.spawn_broadcast_msg){
            Lang.broadcastMSG(str.replaceAll("%times%",String.valueOf(times)));
        }
        dragon.setCustomName(myDragon.display_name);
        setAttribute(dragon, Attribute.GENERIC_MAX_HEALTH, myDragon.max_health);
        dragon.setHealth(myDragon.spawn_health);
        dragon.setMaximumNoDamageTicks(myDragon.no_damage_tick);

        //modifyAttribute(dragon, Attribute.GENERIC_MOVEMENT_SPEED, myDragon.move_speed_modify);//

        modifyAttribute(dragon, Attribute.GENERIC_ARMOR, myDragon.armor_modify);

        modifyAttribute(dragon, Attribute.GENERIC_ARMOR_TOUGHNESS, myDragon.armor_toughness_modify);
        String color = myDragon.glow_color.toUpperCase();
        if(color.equals("NONE")) dragon.setGlowing(false);
        else setGlowingColor(dragon,getGlowColor(color));

        if(Version.mcMainVersion >= 14){
            BossBar bossBar = dragon.getBossBar();
            if(bossBar != null){
                bossBar.setColor(BarColor.valueOf(myDragon.bossbar_color));
                bossBar.setStyle(BarStyle.valueOf(myDragon.bossbar_style));
                if(myDragon.bossbar_create_fog) bossBar.addFlag(BarFlag.CREATE_FOG);
                else bossBar.removeFlag(BarFlag.CREATE_FOG);
                if(myDragon.bossbar_darken_sky) bossBar.addFlag(BarFlag.DARKEN_SKY);
                else bossBar.removeFlag(BarFlag.DARKEN_SKY);
                if(myDragon.bossbar_play_boss_music) bossBar.addFlag(BarFlag.PLAY_BOSS_MUSIC);
                else bossBar.removeFlag(BarFlag.PLAY_BOSS_MUSIC);
            }
        }
        else if(Version.mcMainVersion >= 12){
            getInstance().getBossBarManager().setBossBar(world,myDragon);
        }
        if(Config.advanced_setting_save_bossbar){
            getInstance().getBossBarManager().saveBossBarData(Collections.singletonList(world));
        }
    }

}
