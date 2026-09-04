package com.piratesgaming.unsolved;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.EventPriority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PiratesUnsolved extends JavaPlugin implements Listener {
    private LuckPerms luckPerms;
    private NamespacedKey lifeStarKey;
    private final Set<UUID> loggedIn = ConcurrentHashMap.newKeySet();

    @Override public void onEnable() {
        saveDefaultConfig();
        lifeStarKey = new NamespacedKey(this, "life_star");
        try { luckPerms = LuckPermsProvider.get(); getLogger().info("LuckPerms connected."); }
        catch (Exception e) { getLogger().warning("LuckPerms not found; rank sync/spin will be disabled."); }
        PluginCommand u = getCommand("unsolved");
        if (u != null) { UnsolvedCommand c = new UnsolvedCommand(this); u.setExecutor(c); u.setTabCompleter(c); }
        PluginCommand k = getCommand("kills"); if (k != null) k.setExecutor(new KillsCommand(this));
        PluginCommand l = getCommand("lives"); if (l != null) { LivesCommand c = new LivesCommand(this); l.setExecutor(c); l.setTabCompleter(c); }
        PluginCommand r = getCommand("register"); if (r != null) r.setExecutor(new AuthCommand(this));
        PluginCommand login = getCommand("login"); if (login != null) login.setExecutor(new AuthCommand(this));
        Bukkit.getPluginManager().registerEvents(this, this);
        syncRankOrder();
        for (Player p : Bukkit.getOnlinePlayers()) ensurePlayerData(p);
        getLogger().info("Pirates Unsolved SMP Season 1 enabled.");
    }

    public void reloadPlugin() { reloadConfig(); syncRankOrder(); }
    public void ensurePlayerData(Player p) {
        String u=p.getUniqueId().toString();
        if (!getConfig().contains("kills."+u)) getConfig().set("kills."+u,0);
        if (!getConfig().contains("lives."+u)) getConfig().set("lives."+u,getConfig().getInt("lives-default",10));
        saveConfig();
    }
    public int kills(Player p){ return getConfig().getInt("kills."+p.getUniqueId(),0); }
    public int lives(Player p){ return getConfig().getInt("lives."+p.getUniqueId(),10); }
    public void setKills(Player p,int n){ getConfig().set("kills."+p.getUniqueId(),Math.max(0,n)); saveConfig(); }
    public void setLives(Player p,int n){ getConfig().set("lives."+p.getUniqueId(),Math.max(0,n)); saveConfig(); }

    public void syncRankOrder() {
        if (luckPerms == null) return;
        List<String> groups = new ArrayList<>();
        for (Group g : luckPerms.getGroupManager().getLoadedGroups()) {
            String n=g.getName();
            if (!n.equalsIgnoreCase("default")) groups.add(n);
        }
        groups.sort(String.CASE_INSENSITIVE_ORDER);
        getConfig().set("rank-order", groups);
        saveConfig();
    }
    public List<String> ranks() { return getConfig().getStringList("rank-order"); }
    public String prefix(Player p) {
        if (luckPerms == null) return "";
        User u=luckPerms.getUserManager().getUser(p.getUniqueId()); if(u==null) return "";
        String x=u.getCachedData().getMetaData().getPrefix();
        return x==null?"":ChatColor.translateAlternateColorCodes('&',x);
    }
    public void applyRank(Player p,String group) {
        if(luckPerms==null || group==null || group.isBlank()) return;
        User u=luckPerms.getUserManager().getUser(p.getUniqueId());
        if(u==null) return;
        String current=u.getPrimaryGroup();
        if(current.equalsIgnoreCase(group)) { refreshDisplay(p); return; }
        if(getConfig().getBoolean("remove-previous-groups",false)) {
            for(String g:ranks()) u.data().remove(InheritanceNode.builder(g).build());
        }
        u.data().add(InheritanceNode.builder(group).build());
        luckPerms.getUserManager().saveUser(u);
        refreshDisplay(p);
    }
    public void refreshDisplay(Player p) {
        String pre=prefix(p);
        p.setPlayerListName(ChatColor.translateAlternateColorCodes('&', pre) + p.getName());
        p.setDisplayName(ChatColor.translateAlternateColorCodes('&', pre) + p.getName());
    }
    public void addKill(Player killer) {
        ensurePlayerData(killer); int n=kills(killer)+1; setKills(killer,n);
        killer.sendMessage(ChatColor.GREEN+"Kill recorded. Your kills: "+ChatColor.WHITE+n);
        if(getConfig().getBoolean("kills-rank",false) && n%10==0) {
            int idx=Math.min(n/10-1,ranks().size()-1);
            if(idx>=0) { getConfig().set("rank-index."+killer.getUniqueId(),idx); saveConfig(); applyRank(killer,ranks().get(idx)); killer.sendMessage(ChatColor.GOLD+"Your rank has been upgraded!"); }
        }
    }
    public void spin(Player p) {
        if(luckPerms==null){p.sendMessage(ChatColor.RED+"LuckPerms is required for rank spin.");return;}
        List<String> rs=ranks(); if(rs.isEmpty()){syncRankOrder();rs=ranks();}
        if(rs.isEmpty()){p.sendMessage(ChatColor.RED+"No LuckPerms groups found.");return;}
        int steps=Math.max(1,getConfig().getInt("spin.steps",30)); int delay=Math.max(1,getConfig().getInt("spin.ticks-per-step",2));
        final int[] i={0}; final BukkitTask[] task={null};
        task[0]=Bukkit.getScheduler().runTaskTimer(this,()->{
            String g=rs.get(i[0]%rs.size());
            p.sendTitle(ChatColor.GOLD+"RANK SPIN",ChatColor.YELLOW+g,0,Math.max(2,delay),0);
            p.sendActionBar(ChatColor.AQUA+"Spinning: "+ChatColor.WHITE+g);
            i[0]++;
            if(i[0]>=steps){ task[0].cancel(); String win=rs.get((i[0]-1)%rs.size()); applyRank(p,win); p.sendTitle(ChatColor.GREEN+"WINNER!",ChatColor.GOLD+win,10,50,15); p.sendMessage(ChatColor.GREEN+"Your new LuckPerms rank is: "+ChatColor.GOLD+win); }
        },0,delay);
    }
    public ItemStack lifeStar() {
        ItemStack s=new ItemStack(Material.NETHER_STAR); ItemMeta m=s.getItemMeta();
        m.setDisplayName(ChatColor.LIGHT_PURPLE+"Life Star"); m.setLore(List.of(ChatColor.GRAY+"Special Unsolved SMP life item",ChatColor.YELLOW+"Right-click to gain 1 life"));
        m.getPersistentDataContainer().set(lifeStarKey,PersistentDataType.BYTE,(byte)1); s.setItemMeta(m); return s;
    }
    public boolean isLifeStar(ItemStack s){return s!=null&&s.hasItemMeta()&&s.getItemMeta().getPersistentDataContainer().has(lifeStarKey,PersistentDataType.BYTE);}
    public void ban(Player p,String reason){ getConfig().set("eliminated."+p.getUniqueId(),true); saveConfig(); p.kickPlayer(reason); }
    public String ipHash(String ip){ try { MessageDigest md=MessageDigest.getInstance("SHA-256"); byte[] b=md.digest(ip.getBytes(StandardCharsets.UTF_8)); StringBuilder s=new StringBuilder(); for(byte x:b)s.append(String.format("%02x",x)); return s.toString(); } catch(Exception e){return ip;} }

    public String hashPassword(String password){ try { MessageDigest md=MessageDigest.getInstance("SHA-256"); byte[] b=md.digest(password.getBytes(StandardCharsets.UTF_8)); StringBuilder out=new StringBuilder(); for(byte x:b)out.append(String.format("%02x",x)); return out.toString(); } catch(NoSuchAlgorithmException e){ throw new IllegalStateException(e); } }
    public void register(Player p,String password){ getConfig().set("registered."+p.getUniqueId(),true); getConfig().set("passwords."+p.getUniqueId(),hashPassword(password)); saveConfig(); loggedIn.add(p.getUniqueId()); p.removePotionEffect(PotionEffectType.BLINDNESS); p.sendMessage(ChatColor.GREEN+"Registration successful. Welcome to Unsolved SMP!"); }
    public boolean login(Player p,String password){ if(!getConfig().getBoolean("registered."+p.getUniqueId(),false)) return false; boolean ok=hashPassword(password).equals(getConfig().getString("passwords."+p.getUniqueId(),"")); if(ok){loggedIn.add(p.getUniqueId());p.removePotionEffect(PotionEffectType.BLINDNESS);p.sendMessage(ChatColor.GREEN+"Login successful. Welcome back to Unsolved SMP!");} return ok; }

    @EventHandler(priority=EventPriority.HIGHEST) public void preLogin(AsyncPlayerPreLoginEvent e){
        String uuid=e.getUniqueId().toString();
        if(getConfig().getBoolean("whitelist",false) && !getConfig().getStringList("whitelist-players").stream().anyMatch(x->x.equalsIgnoreCase(e.getName()))) { e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, ChatColor.RED+"You are not whitelisted on Unsolved SMP.\n\nStatus: Permanent\nPlease contact an admin to get whitelisted."); return; }
        if(getConfig().getBoolean("eliminated."+uuid,false)){e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,ChatColor.RED+"You have been eliminated from Unsolved SMP Season 1.");return;}
        if(getConfig().getBoolean("ip-security",false)) {
            String hash=ipHash(e.getAddress().getHostAddress());
            if(getConfig().getStringList("sameip-ips").contains(hash)) return;
            if(getConfig().getStringList("sameip-whitelist").stream().anyMatch(x->x.equalsIgnoreCase(e.getName()))) return;
            String old=getConfig().getString("ip-hashes."+uuid);
            if(old!=null&&!old.equals(hash)){e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,ChatColor.RED+"This account is registered to a different IP.");return;}
            for(String key:getConfig().getConfigurationSection("ip-hashes")!=null?getConfig().getConfigurationSection("ip-hashes").getKeys(false):Collections.<String>emptyList()) if(getConfig().getString("ip-hashes."+key).equals(hash)&&!key.equals(uuid)){e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,ChatColor.RED+"This IP is already registered on Unsolved SMP.");return;}
            getConfig().set("ip-hashes."+uuid,hash); saveConfig();
        }
    }
    @EventHandler public void join(PlayerJoinEvent e){ Player p=e.getPlayer(); ensurePlayerData(p); String h=ipHash(p.getAddress()!=null?p.getAddress().getAddress().getHostAddress():"unknown"); if(getConfig().getStringList("sameip-whitelist").stream().anyMatch(x->x.equalsIgnoreCase(p.getName()))){List<String> ips=getConfig().getStringList("sameip-ips");if(!ips.contains(h)){ips.add(h);getConfig().set("sameip-ips",ips);saveConfig();}} if(getConfig().getBoolean("login-security",false)){p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,6000,1,false,false,false)); if(getConfig().getBoolean("registered."+p.getUniqueId(),false)) p.sendMessage(ChatColor.YELLOW+"Login required. Use /login <password>."); else p.sendMessage(ChatColor.YELLOW+"Register with /register <password> <password>.");} else loggedIn.add(p.getUniqueId()); refreshDisplay(p); }
    @EventHandler public void quit(PlayerQuitEvent e){loggedIn.remove(e.getPlayer().getUniqueId());}
    @EventHandler public void death(PlayerDeathEvent e){ Player p=e.getEntity(); ensurePlayerData(p); int n=lives(p)-1; setLives(p,n); p.sendMessage(ChatColor.RED+"You lost one life. Lives remaining: "+n); if(n<=0&&getConfig().getBoolean("zero-lives-ban",true)){getConfig().set("eliminated."+p.getUniqueId(),true);saveConfig(); Bukkit.getScheduler().runTask(this,()->p.kickPlayer(ChatColor.RED+"You have been eliminated from Unsolved SMP Season 1.\nPlease contact an admin."));} Player k=p.getKiller(); if(k!=null) addKill(k); }
    @EventHandler public void interact(PlayerInteractEvent e){ if(!isLifeStar(e.getItem())) return; if(!e.getAction().isRightClick()) return; Player p=e.getPlayer(); setLives(p,lives(p)+1); e.getItem().setAmount(e.getItem().getAmount()-1); p.sendMessage(ChatColor.GREEN+"You gained one life. Lives: "+lives(p)); e.setCancelled(true); }
    @EventHandler public void craft(CraftItemEvent e){ if(!getConfig().getBoolean("mace",true)&&e.getRecipe().getResult().getType()==Material.MACE)e.setCancelled(true); if(isBanned(e.getRecipe().getResult().getType(),"craft"))e.setCancelled(true); }
    @EventHandler public void pick(PlayerInteractEvent e){ }
    @EventHandler public void pickup(org.bukkit.event.player.EntityPickupItemEvent e){ if(isBanned(e.getItem().getItemStack().getType(),"pickup")) e.setCancelled(true); if(!getConfig().getBoolean("mace",true)&&e.getItem().getItemStack().getType()==Material.HEAVY_CORE)e.setCancelled(true); }
    @EventHandler public void inv(InventoryClickEvent e){ if(e.getCurrentItem()!=null&&isBanned(e.getCurrentItem().getType(),"inventory"))e.setCancelled(true); if(!getConfig().getBoolean("mace",true)&&e.getCurrentItem()!=null&&e.getCurrentItem().getType()==Material.HEAVY_CORE)e.setCancelled(true); }
    @EventHandler public void drop(PlayerDropItemEvent e){ if(isBanned(e.getItemDrop().getItemStack().getType(),"drop"))e.setCancelled(true); }
    @EventHandler public void place(BlockPlaceEvent e){ if(isBanned(e.getItemInHand().getType(),"place"))e.setCancelled(true); }
    @EventHandler public void command(PlayerCommandPreprocessEvent e){ if(getConfig().getBoolean("login-security",false)&&!loggedIn.contains(e.getPlayer().getUniqueId())){String c=e.getMessage().split(" ")[0].toLowerCase(); if(!c.equals("/login")&&!c.equals("/register")&&!c.equals("/l")&&!c.equals("/reg")){e.setCancelled(true);e.getPlayer().sendMessage(ChatColor.RED+"Please register/login first.");}} }
    public boolean isBanned(Material m,String mode){ return getConfig().getBoolean("ban-items."+mode+"."+m.name(),false)||getConfig().getBoolean("ban-items.all."+m.name(),false); }
}
