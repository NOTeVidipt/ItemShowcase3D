package com.vidipt.itemshowcase3d;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.util.*;

public final class ItemShowcase3D extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private final Map<String, Showcase> showcases = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> pendingReturns = new HashMap<>();
    private NamespacedKey showcaseKey;

    @Override public void onEnable() {
        saveDefaultConfig();
        showcaseKey = new NamespacedKey(this, "showcase_id");
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("ishowcase")).setExecutor(this);
        Objects.requireNonNull(getCommand("ishowcase")).setTabCompleter(this);
        loadShowcases(); loadPendingReturns();
        // Reconcile saved showcases with entities and restore label entities when necessary.
        for (Showcase sh : new ArrayList<>(showcases.values())) ensureEntities(sh);
        startDisplayAnimation();
        getLogger().info("ItemShowcase3D v1.1.0 enabled. Use /ishowcase help");
    }
    @Override public void onDisable() { saveShowcases(); savePendingReturns(); }

    private String key(String s) { return s.toLowerCase(Locale.ROOT); }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private void msg(CommandSender s, String text) { s.sendMessage(color(getConfig().getString("messages.prefix", "&8[&bShowcase&8] ") + text)); }
    private boolean admin(CommandSender s) { if (s.hasPermission("itemshowcase3d.admin")) return true; msg(s,"&cYou need itemshowcase3d.admin permission."); return false; }
    private boolean canManage(CommandSender sender, Showcase sh) {
        if (sender.hasPermission("itemshowcase3d.admin")) return true;
        if (!(sender instanceof Player p)) return false;
        return getConfig().getBoolean("allow-owner-removal", true) && p.getUniqueId().toString().equals(sh.owner)
                && p.hasPermission("itemshowcase3d.remove-own");
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            msg(sender,"&f/ishowcase create <name> &7- Display one item from your hand");
            msg(sender,"&f/ishowcase remove <name> &7- Remove your own showcase (admins can remove all)");
            msg(sender,"&f/ishowcase list &7- List showcase names and owners");
            msg(sender,"&f/ishowcase scale <name> <0.2-2.5> &7- Change item size");
            msg(sender,"&f/ishowcase style <name> <pedestal> <glass> &7- Change case materials");
            msg(sender,"&f/ishowcase animate <name> <on|off> &7- Toggle rotation for a showcase");
            msg(sender,"&f/ishowcase text <on|off> &7- Show/hide item name and enchantment text");
            msg(sender,"&f/ishowcase particles <on|off> &7- Toggle showcase particles");
            msg(sender,"&f/ishowcase settings &7- View server defaults");
            msg(sender,"&f/ishowcase setting <option> <value> &7- Change defaults in-game (admin)");
            msg(sender,"&7Options: scale, rotation, names, particles, float, limit, height, pedestal, glass");
            msg(sender,"&f/ishowcase reload &7- Reload configuration"); return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> createCommand(sender,args);
            case "remove" -> removeCommand(sender,args);
            case "list" -> {
                if (showcases.isEmpty()) msg(sender,"&7No showcases created.");
                else for (Showcase sh : showcases.values()) msg(sender,"&b"+sh.id+" &7owner=&f"+ownerName(sh.owner)+" &7scale=&f"+sh.scale+" &7rotation=&f"+(sh.animate?"on":"off"));
            }
            case "scale" -> scaleCommand(sender,args);
            case "style" -> styleCommand(sender,args);
            case "animate" -> animateCommand(sender,args);
            case "text" -> toggleFeatureCommand(sender,args,"show-item-name","Text labels");
            case "particles" -> toggleFeatureCommand(sender,args,"particles.enabled","Particles");
            case "settings" -> settingsCommand(sender);
            case "setting" -> settingCommand(sender,args);
            case "reload" -> reloadCommand(sender);
            default -> msg(sender,"&cUnknown subcommand. Use /ishowcase help.");
        }
        return true;
    }

    private void reloadCommand(CommandSender sender) {
        if (!admin(sender)) return;
        try {
            reloadConfig();
            // Apply newly loaded display-name visibility and refresh existing item displays.
            for (Showcase sh : showcases.values()) {
                refreshDisplay(sh);
            }
            msg(sender, "&aConfiguration reloaded successfully. Existing showcases refreshed.");
            getLogger().info("Configuration reloaded by " + sender.getName() + ".");
        } catch (Exception ex) {
            msg(sender, "&cConfiguration reload failed. Check the server console.");
            getLogger().severe("Failed to reload configuration: " + ex.getMessage());
        }
    }

    private void createCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { msg(sender,"&cPlayers only."); return; }
        if (!getConfig().getBoolean("allow-player-creation",true) || !p.hasPermission("itemshowcase3d.create")) { msg(sender,"&cYou cannot create showcases."); return; }
        if (args.length < 2) { msg(sender,"&cUsage: /ishowcase create <name>"); return; }
        String id=key(args[1]);
        if (!id.matches("[a-z0-9_-]{1,32}")) { msg(sender,"&cUse 1-32 letters, numbers, underscores or hyphens for the name."); return; }
        if (showcases.containsKey(id)) { msg(sender,"&cThat name already exists."); return; }
        ItemStack held=p.getInventory().getItemInMainHand();
        if (held.getType().isAir()) { msg(sender,"&cHold the item you want to display."); return; }
        long owned=showcases.values().stream().filter(x->x.owner.equals(p.getUniqueId().toString())).count();
        if (owned>=getConfig().getInt("max-showcases-per-player",20) && !p.hasPermission("itemshowcase3d.admin")) { msg(sender,"&cShowcase limit reached."); return; }
        Location base=findBase(p);
        if (base==null) { msg(sender,"&cNo safe space nearby. Clear a 1x3 area and try again."); return; }
        ItemStack displayed=held.clone(); displayed.setAmount(1);
        int height=Math.max(1,Math.min(5,getConfig().getInt("glass-height",2)));
        List<String> originals=new ArrayList<>();
        for(int y=0;y<=height;y++) originals.add(base.clone().add(0,y,0).getBlock().getBlockData().getAsString());
        Material pedestal=validMaterial(getConfig().getString("pedestal-material"),Material.SPRUCE_PLANKS);
        Material glass=validGlassMaterial(getConfig().getString("glass-material"));
        Showcase sh=new Showcase(id,p.getUniqueId().toString(),base.getWorld().getName(),base.getBlockX(),base.getBlockY(),base.getBlockZ(),null,null,displayed,
                clampScale(getConfig().getDouble("item-scale",0.8)),getConfig().getBoolean("animate",true),pedestal.name(),glass.name(),height,originals);
        try {
            for(int y=0;y<=height;y++) { if(y==0) base.clone().add(0,y,0).getBlock().setType(pedestal,false); else base.clone().add(0,y,0).getBlock().setType(glass,false); }
            sh=spawnEntities(sh,base);
            showcases.put(id,sh);
            if(held.getAmount()<=1) p.getInventory().setItemInMainHand(null); else held.setAmount(held.getAmount()-1);
            saveShowcases();
            msg(sender,"&aCreated showcase '&f"+id+"&a'. &7One item was taken from your inventory.");
        } catch(Exception ex) {
            getLogger().warning("Could not create showcase '"+id+"': "+ex.getMessage());
            if(sh.entity!=null) { Entity e=Bukkit.getEntity(sh.entity); if(e!=null)e.remove(); }
            if(sh.label!=null) { Entity e=Bukkit.getEntity(sh.label); if(e!=null)e.remove(); }
            restoreOriginals(base,originals);
            msg(sender,"&cCould not create showcase; check console. Your item was not consumed.");
        }
    }
    private Location findBase(Player p) {
        Location feet=p.getLocation().getBlock().getLocation();
        Location[] candidates={feet,feet.clone().add(1,0,0),feet.clone().add(-1,0,0),feet.clone().add(0,0,1),feet.clone().add(0,0,-1)};
        int height=Math.max(1,Math.min(5,getConfig().getInt("glass-height",2)));
        for(Location base:candidates) { boolean free=true; for(int y=0;y<=height;y++){Block b=base.clone().add(0,y,0).getBlock(); if(!b.isPassable()&&!b.isLiquid()){free=false;break;}} if(free)return base; }
        return null;
    }
    private void removeCommand(CommandSender sender,String[] args) {
        if(args.length<2){msg(sender,"&cUsage: /ishowcase remove <name>");return;}
        Showcase sh=showcases.get(key(args[1])); if(sh==null){msg(sender,"&cShowcase not found.");return;}
        if(!canManage(sender,sh)){msg(sender,"&cYou can only remove your own showcases. Admins can remove all.");return;}
        showcases.remove(sh.id); destroy(sh,true); returnItemToOwner(sh); saveShowcases(); savePendingReturns();
        msg(sender,"&aShowcase removed. &7The displayed item will be returned to its creator.");
    }
    private void scaleCommand(CommandSender sender,String[] args) {
        if(args.length<3){msg(sender,"&cUsage: /ishowcase scale <name> <0.2-2.5>");return;}
        Showcase sh=showcases.get(key(args[1])); if(sh==null){msg(sender,"&cShowcase not found.");return;}
        if(!canManage(sender,sh)){msg(sender,"&cYou cannot edit this showcase.");return;}
        try { double value=Double.parseDouble(args[2]); if(!Double.isFinite(value)||value<getConfig().getDouble("min-item-scale",0.2)||value>getConfig().getDouble("max-item-scale",2.5)){msg(sender,"&cScale must be between "+getConfig().getDouble("min-item-scale",0.2)+" and "+getConfig().getDouble("max-item-scale",2.5)+".");return;} sh=withScale(sh,(float)value); showcases.put(sh.id,sh); refreshDisplay(sh); saveShowcases(); msg(sender,"&aItem scale set to &f"+value+"&a."); }
        catch(NumberFormatException ex){msg(sender,"&cEnter a valid number, for example 1.2.");}
    }
    private void styleCommand(CommandSender sender,String[] args) {
        if(args.length<4){msg(sender,"&cUsage: /ishowcase style <name> <pedestal-material> <glass-material>");return;}
        Showcase sh=showcases.get(key(args[1])); if(sh==null){msg(sender,"&cShowcase not found.");return;}
        if(!canManage(sender,sh)){msg(sender,"&cYou cannot edit this showcase.");return;}
        Material ped=Material.matchMaterial(args[2]),glass=Material.matchMaterial(args[3]);
        if(ped==null||!ped.isBlock()||ped.isAir()||glass==null||(!glass.isBlock()&&!glass.isAir())){msg(sender,"&cUse a valid pedestal block and a valid glass block or AIR, e.g. SPRUCE_PLANKS GLASS or SPRUCE_PLANKS AIR.");return;}
        World w=Bukkit.getWorld(sh.world); if(w==null){msg(sender,"&cShowcase world is unavailable.");return;}
        Location base=new Location(w,sh.x,sh.y,sh.z);
        for(int y=0;y<=sh.height;y++) base.clone().add(0,y,0).getBlock().setType(y==0?ped:glass,false);
        sh=withStyle(sh,ped.name(),glass.name()); showcases.put(sh.id,sh); saveShowcases(); msg(sender,"&aShowcase style updated.");
    }
    private void animateCommand(CommandSender sender,String[] args) {
        if(args.length<3||!(args[2].equalsIgnoreCase("on")||args[2].equalsIgnoreCase("off"))){msg(sender,"&cUsage: /ishowcase animate <name> <on|off>");return;}
        Showcase sh=showcases.get(key(args[1]));if(sh==null){msg(sender,"&cShowcase not found.");return;}
        if(!canManage(sender,sh)){msg(sender,"&cYou cannot edit this showcase.");return;}
        sh=withAnimate(sh,args[2].equalsIgnoreCase("on"));showcases.put(sh.id,sh);saveShowcases();msg(sender,"&aRotation "+(sh.animate?"enabled":"disabled")+".");
    }

    private void toggleFeatureCommand(CommandSender sender, String[] args, String path, String label) {
        if (!admin(sender)) return;
        if (args.length < 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            msg(sender, "&cUsage: /ishowcase " + (path.equals("show-item-name") ? "text" : "particles") + " <on|off>");
            return;
        }
        boolean enabled = args[1].equalsIgnoreCase("on");
        getConfig().set(path, enabled);
        saveConfig();
        if (path.equals("show-item-name")) {
            for (Showcase sh : showcases.values()) refreshDisplay(sh);
        }
        msg(sender, "&a" + label + " " + (enabled ? "&aenabled" : "&cdisabled") + "&a. Setting saved.");
    }

    private void settingsCommand(CommandSender sender) {
        var c = getConfig();
        msg(sender,"&eCurrent server defaults:");
        msg(sender,"&7Scale: &f"+c.getDouble("item-scale",0.8)+" &7| Rotation: &f"+c.getDouble("rotation-degrees-per-second",45.0)+" deg/s");
        msg(sender,"&7Text (names + enchantments): &f"+onOff(c.getBoolean("show-item-name",true))+" &7| Particles: &f"+onOff(c.getBoolean("particles.enabled",false))+" &7| Floating: &f"+onOff(c.getBoolean("floating-animation",true)));
        msg(sender,"&7Limit: &f"+c.getInt("max-showcases-per-player",20)+" &7| Case height: &f"+c.getInt("glass-height",2));
        msg(sender,"&7Pedestal: &f"+c.getString("pedestal-material","SPRUCE_PLANKS")+" &7| Glass: &f"+c.getString("glass-material","GLASS"));
        msg(sender,"&7Change defaults: &f/ishowcase setting <scale|rotation|names|particles|float|limit|height|pedestal|glass> <value>");
    }

    private String onOff(boolean value) { return value ? "on" : "off"; }

    private void settingCommand(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (args.length < 3) { msg(sender,"&cUsage: /ishowcase setting <scale|rotation|names|particles|float|limit|height|pedestal|glass> <value>"); return; }
        String option=args[1].toLowerCase(Locale.ROOT), value=args[2];
        try {
            switch(option) {
                case "scale" -> {
                    double v=Double.parseDouble(value);
                    if(!Double.isFinite(v)||v<getConfig().getDouble("min-item-scale",0.2)||v>getConfig().getDouble("max-item-scale",2.5)){msg(sender,"&cScale must be between "+getConfig().getDouble("min-item-scale",0.2)+" and "+getConfig().getDouble("max-item-scale",2.5)+".");return;}
                    getConfig().set("item-scale",v);
                }
                case "rotation" -> { double v=Double.parseDouble(value); if(!Double.isFinite(v)||v<0||v>360){msg(sender,"&cRotation must be between 0 and 360 degrees per second.");return;} getConfig().set("rotation-degrees-per-second",v); }
                case "names", "particles", "float" -> {
                    if(!value.equalsIgnoreCase("on")&&!value.equalsIgnoreCase("off")){msg(sender,"&cUse on or off.");return;}
                    String path=option.equals("names")?"show-item-name":option.equals("particles")?"particles.enabled":"floating-animation";
                    getConfig().set(path,value.equalsIgnoreCase("on"));
                }
                case "limit" -> { int v=Integer.parseInt(value); if(v<1||v>500){msg(sender,"&cLimit must be 1-500.");return;} getConfig().set("max-showcases-per-player",v); }
                case "height" -> { int v=Integer.parseInt(value); if(v<1||v>5){msg(sender,"&cHeight must be 1-5 blocks.");return;} getConfig().set("glass-height",v); }
                case "pedestal", "glass" -> {
                    Material m=Material.matchMaterial(value);
                    if(m==null||(!m.isBlock()&&!m.isAir())||(option.equals("pedestal")&&m.isAir())){msg(sender,"&cInvalid material. Pedestal must be a block; glass can be a block or AIR.");return;}
                    getConfig().set(option.equals("pedestal")?"pedestal-material":"glass-material",m.name());
                }
                default -> { msg(sender,"&cUnknown setting. Use /ishowcase settings."); return; }
            }
            saveConfig();
            if(option.equals("names")) for(Showcase sh:showcases.values()) refreshDisplay(sh);
            msg(sender,"&aDefault setting '&f"+option+"&a' updated to '&f"+value+"&a'. Saved automatically; no config.yml editing needed.");
        } catch(NumberFormatException ex) { msg(sender,"&cInvalid number. Example: /ishowcase setting scale 1.2"); }
    }

    private void startDisplayAnimation() {
        getServer().getScheduler().runTaskTimer(this, new Runnable() {
            private final Map<UUID,Float> angles=new HashMap<>(); private long tick=0;
            @Override public void run() {
                tick++;
                for(Showcase sh:showcases.values()) {
                    Entity entity=sh.entity==null?null:Bukkit.getEntity(sh.entity);
                    if(!(entity instanceof ItemDisplay display)||!display.isValid()) continue;
                    if(sh.animate&&getConfig().getBoolean("animate",true)) {
                        float step=(float)(Math.toRadians(getConfig().getDouble("rotation-degrees-per-second",45.0))/20.0);
                        float angle=(angles.getOrDefault(sh.entity,0f)+step)%(float)(Math.PI*2); angles.put(sh.entity,angle);
                        float bob=getConfig().getBoolean("floating-animation",true)?(float)(Math.sin(tick*0.08)*0.06):0f;
                        Location loc=new Location(display.getWorld(),sh.x+0.5,sh.y+1.65+bob,sh.z+0.5);
                        display.teleport(loc);
                        applyTransform(display,sh.scale,angle);
                    }
                    if(getConfig().getBoolean("particles.enabled",false)&&tick%6==0) {
                        Particle particle = particleFor(sh.item);
                        Location center = new Location(display.getWorld(), sh.x + 0.5, sh.y + 1.65, sh.z + 0.5);
                        int count = Math.max(1, Math.min(8, getConfig().getInt("particles.count", 3)));
                        display.getWorld().spawnParticle(particle, center, count, 0.24, 0.30, 0.24, 0.015);
                        if (hasEnchantments(sh.item) && tick % 18 == 0) {
                            try { display.getWorld().spawnParticle(Particle.valueOf("ENCHANT"), center, 5, 0.30, 0.35, 0.30, 0.6); }
                            catch (IllegalArgumentException ignored) { }
                        }
                    }
                }
            }
        },1L,1L);
    }
    private void applyTransform(ItemDisplay display,float scale,float angle){display.setTransformation(new Transformation(new Vector3f(0,0,0),new AxisAngle4f(angle,0,1,0),new Vector3f(scale,scale,scale),new AxisAngle4f(0,0,1,0)));}
    private Showcase spawnEntities(Showcase sh,Location base) {
        World w=Objects.requireNonNull(base.getWorld());
        ItemDisplay display=(ItemDisplay)w.spawnEntity(base.clone().add(0.5,1.65,0.5),EntityType.ITEM_DISPLAY);
        display.setItemStack(sh.item.clone());display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);display.setPersistent(true);display.setInvulnerable(true);display.setSilent(true);
        display.getPersistentDataContainer().set(showcaseKey,PersistentDataType.STRING,sh.id);applyTransform(display,sh.scale,0);
        TextDisplay label=(TextDisplay)w.spawnEntity(base.clone().add(0.5,getConfig().getDouble("name-height",2.55),0.5),EntityType.TEXT_DISPLAY);
        label.setText(displayText(sh.item));label.setBillboard(Display.Billboard.CENTER);label.setAlignment(TextDisplay.TextAlignment.CENTER);label.setSeeThrough(true);label.setShadowed(true);label.setDefaultBackground(false);label.setPersistent(true);label.setInvulnerable(true);label.setSilent(true);
        label.getPersistentDataContainer().set(showcaseKey,PersistentDataType.STRING,sh.id);label.setVisibleByDefault(getConfig().getBoolean("show-item-name",true));
        return withEntities(sh,display.getUniqueId(),label.getUniqueId());
    }
    private String displayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) return meta.getDisplayName();
        String n = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String word : n.split(" ")) {
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return "&b✦ &f" + out;
    }
    private Map<Enchantment, Integer> enchantmentsOf(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return Collections.emptyMap();
        if (meta instanceof EnchantmentStorageMeta book && book.hasStoredEnchants()) return book.getStoredEnchants();
        return meta.getEnchants();
    }
    private boolean hasEnchantments(ItemStack item) { return !enchantmentsOf(item).isEmpty(); }
    private String displayText(ItemStack item) {
        if (!getConfig().getBoolean("show-item-name", true)) return "";
        StringBuilder text = new StringBuilder(color(displayName(item)));
        Map<Enchantment, Integer> enchants = enchantmentsOf(item);
        if (!enchants.isEmpty()) {
            int shown = 0;
            for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
                if (shown >= 8) { text.append("\n").append(ChatColor.GRAY).append("+ ").append(enchants.size() - shown).append(" more"); break; }
                text.append("\n").append(ChatColor.LIGHT_PURPLE).append(prettyEnchant(entry.getKey())).append(" ").append(roman(entry.getValue()));
                shown++;
            }
        }
        return text.toString();
    }
    private String prettyEnchant(Enchantment enchantment) {
        String raw = enchantment.getKey().getKey().replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String word : raw.split(" ")) { if (!out.isEmpty()) out.append(' '); out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)); }
        return out.toString();
    }
    private String roman(int number) {
        if (number <= 0 || number > 20) return Integer.toString(number);
        int[] values = {10,9,5,4,1}; String[] numerals = {"X","IX","V","IV","I"}; StringBuilder out = new StringBuilder();
        for (int i=0;i<values.length;i++) while (number >= values[i]) { out.append(numerals[i]); number -= values[i]; }
        return out.toString();
    }
    private Particle particleFor(ItemStack item) {
        String material = item.getType().name();
        String selected = "END_ROD";
        if (hasEnchantments(item)) selected = "ENCHANT";
        else if (material.contains("GOLD") || material.contains("GILDED")) selected = "WAX_ON";
        else if (material.contains("EMERALD") || material.contains("SLIME")) selected = "HAPPY_VILLAGER";
        else if (material.contains("NETHER") || material.contains("CRIMSON") || material.contains("WARPED") || material.contains("SOUL")) selected = "SOUL_FIRE_FLAME";
        else if (material.contains("DIAMOND") || material.contains("PRISMARINE")) selected = "ELECTRIC_SPARK";
        try { return Particle.valueOf(selected); } catch (IllegalArgumentException ex) { return Particle.END_ROD; }
    }
    private void ensureEntities(Showcase sh) {
        World w=Bukkit.getWorld(sh.world);if(w==null)return;
        Entity item=sh.entity==null?null:Bukkit.getEntity(sh.entity); Entity label=sh.label==null?null:Bukkit.getEntity(sh.label);
        if(!(item instanceof ItemDisplay)||!item.isValid()||!(label instanceof TextDisplay)||!label.isValid()) {
            if(item!=null)item.remove();if(label!=null)label.remove();
            Location base=new Location(w,sh.x,sh.y,sh.z);Showcase fixed=spawnEntities(sh,base);showcases.put(sh.id,fixed);
        }
    }
    private void refreshDisplay(Showcase sh){World w=Bukkit.getWorld(sh.world);if(w==null)return;Entity e=sh.entity==null?null:Bukkit.getEntity(sh.entity);if(e instanceof ItemDisplay d){d.setItemStack(sh.item.clone());applyTransform(d,sh.scale,0);}Entity l=sh.label==null?null:Bukkit.getEntity(sh.label);if(l instanceof TextDisplay t){t.setText(displayText(sh.item));t.setVisibleByDefault(getConfig().getBoolean("show-item-name",true));}}
    private Material validMaterial(String name,Material fallback){Material m=Material.matchMaterial(name==null?"":name);return m==null||!m.isBlock()||m.isAir()?fallback:m;}
    private Material validGlassMaterial(String name){Material m=Material.matchMaterial(name==null?"":name);return m!=null&&(m.isBlock()||m.isAir())?m:Material.GLASS;}
    private float clampScale(double scale){return (float)Math.max(getConfig().getDouble("min-item-scale",0.2),Math.min(getConfig().getDouble("max-item-scale",2.5),Double.isFinite(scale)?scale:0.8));}
    private void restoreOriginals(Location base,List<String> originals){for(int y=0;y<originals.size();y++){try{BlockData data=Bukkit.createBlockData(originals.get(y));base.clone().add(0,y,0).getBlock().setBlockData(data,false);}catch(Exception ex){getLogger().warning("Could not restore original block at "+base.clone().add(0,y,0));}}}
    private void destroy(Showcase sh,boolean restore){World w=Bukkit.getWorld(sh.world);if(w==null)return;Entity e=sh.entity==null?null:Bukkit.getEntity(sh.entity);if(e!=null)e.remove();Entity l=sh.label==null?null:Bukkit.getEntity(sh.label);if(l!=null)l.remove();if(restore)restoreOriginals(new Location(w,sh.x,sh.y,sh.z),sh.originals);}
    private boolean isShowcaseBlock(Block b,Showcase sh){return sh.world.equals(b.getWorld().getName())&&b.getX()==sh.x&&b.getZ()==sh.z&&b.getY()>=sh.y&&b.getY()<=sh.y+sh.height;}
    @EventHandler public void onBreak(BlockBreakEvent e){for(Showcase sh:showcases.values())if(isShowcaseBlock(e.getBlock(),sh)){if(!e.getPlayer().hasPermission("itemshowcase3d.admin")){e.setCancelled(true);msg(e.getPlayer(),"&cYou cannot break a showcase block. Use /ishowcase remove <name>.");}return;}}
    @EventHandler public void onDamage(EntityDamageEvent e){String id=e.getEntity().getPersistentDataContainer().get(showcaseKey,PersistentDataType.STRING);if(id!=null&&showcases.containsKey(id))e.setCancelled(true);else for(Showcase sh:showcases.values())if(Objects.equals(sh.entity,e.getEntity().getUniqueId())||Objects.equals(sh.label,e.getEntity().getUniqueId())){e.setCancelled(true);return;}}
    @EventHandler public void onExplode(EntityExplodeEvent e){e.blockList().removeIf(b->showcases.values().stream().anyMatch(sh->isShowcaseBlock(b,sh)));}
    @EventHandler public void onInteract(PlayerInteractEntityEvent e){String id=e.getRightClicked().getPersistentDataContainer().get(showcaseKey,PersistentDataType.STRING);if(id!=null&&showcases.containsKey(id))e.setCancelled(true);else for(Showcase sh:showcases.values())if(Objects.equals(sh.entity,e.getRightClicked().getUniqueId())||Objects.equals(sh.label,e.getRightClicked().getUniqueId())){e.setCancelled(true);return;}}
    @EventHandler public void onJoin(PlayerJoinEvent e){List<ItemStack> items=pendingReturns.remove(e.getPlayer().getUniqueId());if(items==null||items.isEmpty())return;for(ItemStack item:items){Map<Integer,ItemStack> left=e.getPlayer().getInventory().addItem(item.clone());for(ItemStack stack:left.values())e.getPlayer().getWorld().dropItemNaturally(e.getPlayer().getLocation(),stack);}savePendingReturns();e.getPlayer().sendMessage(color("&8[&bShowcase&8] &aYour removed showcase item(s) have been returned."));}
    private void returnItemToOwner(Showcase sh){UUID id;try{id=UUID.fromString(sh.owner);}catch(Exception ex){getLogger().warning("Invalid owner UUID for showcase "+sh.id);return;}Player p=Bukkit.getPlayer(id);if(p!=null){Map<Integer,ItemStack> left=p.getInventory().addItem(sh.item.clone());for(ItemStack stack:left.values())p.getWorld().dropItemNaturally(p.getLocation(),stack);msg(p,"&aYour item from showcase '&f"+sh.id+"&a' was returned.");}else pendingReturns.computeIfAbsent(id,k->new ArrayList<>()).add(sh.item.clone());}
    private String ownerName(String raw){try{OfflinePlayer p=Bukkit.getOfflinePlayer(UUID.fromString(raw));return p.getName()==null?raw.substring(0,Math.min(8,raw.length())):p.getName();}catch(Exception e){return "unknown";}}
    private void savePendingReturns(){var root=new org.bukkit.configuration.file.YamlConfiguration();for(var entry:pendingReturns.entrySet())root.set("pending."+entry.getKey(),entry.getValue());try{root.save(new File(getDataFolder(),"pending-returns.yml"));}catch(Exception e){getLogger().severe("Could not save pending returns: "+e.getMessage());}}
    private void loadPendingReturns(){File f=new File(getDataFolder(),"pending-returns.yml");if(!f.exists())return;var root=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);var sec=root.getConfigurationSection("pending");if(sec==null)return;for(String raw:sec.getKeys(false))try{UUID id=UUID.fromString(raw);List<ItemStack> items=new ArrayList<>();for(Object o:root.getList("pending."+raw,Collections.emptyList()))if(o instanceof ItemStack i)items.add(i.clone());if(!items.isEmpty())pendingReturns.put(id,items);}catch(Exception ex){getLogger().warning("Invalid pending return UUID: "+raw);}}
    private void saveShowcases(){var root=new org.bukkit.configuration.file.YamlConfiguration();for(Showcase s:showcases.values()){String p="showcases."+s.id+".";root.set(p+"owner",s.owner);root.set(p+"world",s.world);root.set(p+"x",s.x);root.set(p+"y",s.y);root.set(p+"z",s.z);root.set(p+"entity",s.entity==null?null:s.entity.toString());root.set(p+"label",s.label==null?null:s.label.toString());root.set(p+"item",s.item);root.set(p+"scale",s.scale);root.set(p+"animate",s.animate);root.set(p+"pedestal",s.pedestal);root.set(p+"glass",s.glass);root.set(p+"height",s.height);root.set(p+"originals",s.originals);}try{root.save(new File(getDataFolder(),"showcases.yml"));}catch(Exception e){getLogger().severe("Could not save showcases.yml: "+e.getMessage());}}
    private void loadShowcases(){File f=new File(getDataFolder(),"showcases.yml");if(!f.exists())return;var root=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);var sec=root.getConfigurationSection("showcases");if(sec==null)return;for(String id:sec.getKeys(false)){String p="showcases."+id+".";String world=root.getString(p+"world","");World w=Bukkit.getWorld(world);if(w==null)continue;ItemStack item=root.getItemStack(p+"item");if(item==null)continue;UUID entity=parseUuid(root.getString(p+"entity")),label=parseUuid(root.getString(p+"label"));List<String> originals=root.getStringList(p+"originals");int height=root.getInt(p+"height",Math.max(1,getConfig().getInt("glass-height",2)));if(originals.isEmpty()){for(int y=0;y<=height;y++)originals.add("minecraft:air");}showcases.put(id,new Showcase(id,root.getString(p+"owner",""),world,root.getInt(p+"x"),root.getInt(p+"y"),root.getInt(p+"z"),entity,label,item,clampScale(root.getDouble(p+"scale",getConfig().getDouble("item-scale",0.8))),root.getBoolean(p+"animate",getConfig().getBoolean("animate",true)),root.getString(p+"pedestal",getConfig().getString("pedestal-material","SPRUCE_PLANKS")),root.getString(p+"glass",getConfig().getString("glass-material","GLASS")),height,originals));}}
    private UUID parseUuid(String s){try{return s==null?null:UUID.fromString(s);}catch(Exception e){return null;}}
    private Showcase withEntities(Showcase s,UUID entity,UUID label){return new Showcase(s.id,s.owner,s.world,s.x,s.y,s.z,entity,label,s.item,s.scale,s.animate,s.pedestal,s.glass,s.height,s.originals);}
    private Showcase withScale(Showcase s,float scale){return new Showcase(s.id,s.owner,s.world,s.x,s.y,s.z,s.entity,s.label,s.item,scale,s.animate,s.pedestal,s.glass,s.height,s.originals);}
    private Showcase withStyle(Showcase s,String ped,String glass){return new Showcase(s.id,s.owner,s.world,s.x,s.y,s.z,s.entity,s.label,s.item,s.scale,s.animate,ped,glass,s.height,s.originals);}
    private Showcase withAnimate(Showcase s,boolean animate){return new Showcase(s.id,s.owner,s.world,s.x,s.y,s.z,s.entity,s.label,s.item,s.scale,animate,s.pedestal,s.glass,s.height,s.originals);}
    @Override public List<String> onTabComplete(CommandSender s,Command c,String alias,String[] args){
        if(args.length==1)return List.of("create","remove","list","scale","style","animate","text","particles","settings","setting","reload","help").stream().filter(x->x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2&&List.of("remove","scale","style","animate").contains(args[0].toLowerCase(Locale.ROOT)))return showcases.keySet().stream().filter(x->x.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2&&List.of("text","particles").contains(args[0].toLowerCase(Locale.ROOT)))return List.of("on","off").stream().filter(x->x.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2&&args[0].equalsIgnoreCase("setting"))return List.of("scale","rotation","names","particles","float","limit","height","pedestal","glass").stream().filter(x->x.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args.length==3&&List.of("animate").contains(args[0].toLowerCase(Locale.ROOT)))return List.of("on","off");
        if(args.length==3&&args[0].equalsIgnoreCase("setting")&&List.of("names","particles","float").contains(args[1].toLowerCase(Locale.ROOT)))return List.of("on","off");
        if(args.length==3&&args[0].equalsIgnoreCase("setting")&&List.of("pedestal","glass").contains(args[1].toLowerCase(Locale.ROOT)))return Arrays.stream(Material.values()).filter(m->m.isBlock()||m.isAir()).map(Material::name).filter(x->x.startsWith(args[2].toUpperCase(Locale.ROOT))).limit(80).toList();
        if(args.length==4&&args[0].equalsIgnoreCase("style")&&args[1].length()>0)return Arrays.stream(Material.values()).filter(m->m.isBlock()||m.isAir()).map(Material::name).filter(x->x.startsWith(args[3].toUpperCase(Locale.ROOT))).limit(80).toList();
        return Collections.emptyList();
    }
    private record Showcase(String id,String owner,String world,int x,int y,int z,UUID entity,UUID label,ItemStack item,float scale,boolean animate,String pedestal,String glass,int height,List<String> originals){}
}
