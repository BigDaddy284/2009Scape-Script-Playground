// Code Edition block runtime. The Script Playground turns a block script into calls to the functions below.
// Every action checks what it needs first; when it can't go on it explains why in the game chat and stops.
"use strict";

class BlockStop extends Error {}

const blocks = (() => {
  const SECOND = 1000;
  let current = "";

  function step(id) { current = id; __api.block(id); }
  function stop(reason) { throw new BlockStop(reason); }
  function lc(s) { return String(s || "").toLowerCase(); }
  function free() { __refresh(); return character.free_slots; }
  function near(list) { list.sort((a, b) => a.distance - b.distance); return list[0] || null; }

  // waits up to ms for check() to give something truthy (polled once per tick); null if it never does
  function waitFor(check, ms) { return wait_until(check, ms).catch(() => null); }
  function idle(ms) { return until_idle(ms).catch(() => null); }

  // ---- what a target name means ----
  const TREES = {"tree": "Tree", "oak": "Oak", "willow": "Willow", "maple": "Maple tree", "maple tree": "Maple tree",
    "yew": "Yew", "magic": "Magic tree", "magic tree": "Magic tree", "dead tree": "Dead tree", "evergreen": "Evergreen"};
  const FISH = {"shrimp": "net", "anchovies": "net", "sardine": "bait", "herring": "bait", "trout": "lure", "salmon": "lure",
    "pike": "bait", "lobster": "cage", "tuna": "harpoon", "swordfish": "harpoon", "net": "net", "bait": "bait", "lure": "lure",
    "cage": "cage", "harpoon": "harpoon"};

  // ---- the game catalogue (catalog.json): places, items, recipes and their levels ----
  let CAT = null;
  function cat() { if (!CAT) { try { CAT = JSON.parse(__api.catalog()); } catch (e) { CAT = {}; } } return CAT; }
  // names compared without case, spaces or brackets: "Guam potion (unf)" is "Guam potion(unf)"
  function norm(s) { return lc(s).replace(/[^a-z0-9]/g, ""); }
  function same(a, b) { return norm(a) === norm(b); }
  function cap(s) { s = String(s || ""); return s.charAt(0).toUpperCase() + s.slice(1); }
  // map coordinates typed as "3222, 3218" or "3222, 3218, 1" (x, y and the floor, 0 when left out)
  function coordsOf(name) {
    const m = String(name || "").trim().match(/^\(?\s*(\d{3,5})\s*[,\s]\s*(\d{3,5})(?:\s*[,\s]\s*(\d))?\s*\)?$/);
    return m ? {x: +m[1], y: +m[2], z: m[3] === undefined ? 0 : +m[3]} : null;
  }
  function placeFor(name) {
    const xy = coordsOf(name);
    if (xy) return xy;
    if (PLACES[lc(name)]) return PLACES[lc(name)];
    const p = (cat().places || []).find(q => same(q.name, name))
           || (((cat().skills || {}).agility || {}).courses || []).find(c => c.x && (same(c.course, name) || same(c.course, name + " course")));
    return p ? {x: p.x, y: p.y, z: p.z} : null;
  }
  // where a person or monster can be found ({x, y, z}), nearest first: the same floor and above/below ground count most
  function spawnSpots(name) {
    const c = cat(), key = Object.keys(c.npcs || {}).find(k => same(k, name)) || Object.keys(c.monsters || {}).find(k => same(k, name));
    if (!key) return [];
    const e = (c.npcs || {})[key] || (c.monsters || {})[key];
    __refresh();
    const cost = p => Math.hypot(p[0] - character.x, p[1] - character.y) + (p[2] !== character.z ? 30 : 0) + ((p[1] >= 6400) !== (character.y >= 6400) ? 400 : 0);
    return (e.at || []).slice().sort((a, b) => cost(a) - cost(b)).map(p => ({x: p[0], y: p[1], z: p[2]}));
  }
  function inv() { return items(); }
  // 1-based inventory slots holding an item with this name
  function slotsWith(name) { return inv().filter(i => same(i.name, name)).map(i => i.slot + 1); }
  function itemInSlot(n) { return inv().find(i => i.slot === n - 1) || null; }
  function levelOf(skill) { __refresh(); return (character.levels || {})[lc(skill)] || 1; }
  // the recipe for using a on b (either way round), if the catalogue knows one
  function recipeFor(a, b) {
    return (cat().recipes || []).filter(r => (same(r.use, a) && same(r.on, b)) || (same(r.use, b) && same(r.on, a)));
  }
  // why the character can't make this recipe yet, or "" when it can
  function cantMake(rs) {
    if (!rs.length) return "";
    const ok = rs.filter(r => levelOf(r.skill) >= r.level);
    if (!ok.length) {
      const r = rs.reduce((a, b) => a.level <= b.level ? a : b);
      return `you need ${cap(r.skill)} level ${r.level} to make ${r.makes}. Yours is ${levelOf(r.skill)}.`;
    }
    const q = ok.find(r => r.quest && __api.questState(r.quest) !== "done");
    if (q && ok.every(r => r.quest && __api.questState(r.quest) !== "done"))
      return `you must complete the ${q.quest} quest before you can make ${q.makes}.`;
    return "";
  }
  function objectsNamed(name, radius) { return get_objects(name, radius || 30); }
  function monstersNamed(name, radius) {
    return get_monsters(name, radius || 30).filter(m => m.hp > 0);
  }

  // ---- actions ----
  async function walk_to(id, target) {
    step(id);
    const xy = coordsOf(target);
    if (xy) {
      try { await smart_move(xy, {within: 0, loose: 3}); }
      catch (e) { stop(`could not walk to ${xy.x}, ${xy.y}${xy.z ? " (floor " + xy.z + ")" : ""}: ${e.message}.`); }
      return;
    }
    const place = placeFor(target);
    if (place) {
      try { await smart_move(place, {within: 3, loose: 8}); }
      catch (e) { stop(`could not walk to ${target}: the way is blocked (${e.message}).`); }
      return;
    }
    // a fish ("Shrimps", "Lobster"): the fishing spot for it that is easiest to walk to
    const fm = !objectsNamed(target).length && !monstersNamed(target).length ? fishMethod(target, true) : null;
    if (fm) {
      const what = fishName(fm.fish), far = fishingSpotsFor(fm);
      if (!far.length) {
        if (far.skipped.guild) stop(`the only ${what} spots you can walk to are in the Fishing Guild, which needs Fishing level 68.`);
        if (far.skipped.wildy) stop(`the only ${what} spots are in the Wilderness, so I won't walk you there.`);
        stop(`every ${what} fishing spot is somewhere you can't walk to from here (an island or a quest area). Travel closer first.`);
      }
      let why = "";
      for (const dest of far) {
        if (get_monsters("Fishing spot", 12).some(s => !__api.fishCheckMethod(s.index, fm.m.method).startsWith("you can't"))) return;
        try { await smart_move(dest, {within: 3, loose: 10}); return; }
        catch (e) { why = e.message; }
      }
      stop(`could not walk to a ${what} fishing spot: ${why}.`);
    }
    // an object or monster: try the nearest few, since the very nearest can be behind a fence or across water
    const trees = objectsNamed(TREES[lc(target)] || target).sort((a, b) => a.distance - b.distance).map(o => ({x: o.x, y: o.y, z: character.z}));
    const mons = monstersNamed(target).sort((a, b) => a.distance - b.distance).map(m => ({x: m.x, y: m.y, z: m.z}));
    const options = (trees.length ? trees : mons).slice(0, 4);
    if (!options.length) {
      // a person or monster further away: go to where the game puts them, then up to them
      const spots = spawnSpots(target);
      if (!spots.length) stop(`there is no ${target} nearby and it isn't a place or person I know. Pick one from the lists, or walk closer first.`);
      const c0 = cat(), e0 = Object.entries(Object.assign({}, c0.monsters || {}, c0.npcs || {})).find(([k]) => same(k, target));
      const d0 = Math.hypot(spots[0].x - character.x, spots[0].y - character.y);
      if (e0 && e0[1].walk === false && (d0 > 100 || spots[0].z !== character.z))
        stop(`${target} is somewhere Walk To can't get to on foot (a dungeon, an island, a minigame or a quest area). Teleport near them first, then Walk To works from there.`);
      let why = "";
      for (const dest of spots.slice(0, 3)) {
        try { await smart_move(dest, {within: 4}); why = ""; break; }
        catch (e) { why = e.message; }
      }
      // up to the person themself (they wander, and can stand behind a counter or fence)
      const here = monstersNamed(target, 20).sort((a, b) => a.distance - b.distance)[0];
      if (here) await smart_move({x: here.x, y: here.y, z: here.z}, {within: 2}).catch(() => {});
      const now = monstersNamed(target, 20).sort((a, b) => a.distance - b.distance)[0];
      if (now && now.z === character.z && now.distance <= 4) return;
      if (why) stop(`could not walk to ${target}: ${why}.`);
      return;
    }
    let last = "";
    for (const dest of options) {
      try { await smart_move(dest, {within: 2}); return; }
      catch (e) {
        last = e.message;
        if (__api.distanceTo(dest.x, dest.y) <= 4) return;    // close enough to reach it from here
      }
    }
    stop(`could not walk to ${target}: the way is blocked (${last}).`);
  }

  // the axe or pickaxe check: "you need a pickaxe" or "your Rune pickaxe needs Mining level 41"
  function toolCheck(skill, table, kind) {
    const tools = ((cat().skills || {})[skill] || {})[table] || [];
    const mine = tools.filter(t => slotsWith(t.name).length || equipment().some(e => same(e.name, t.name)));
    if (!mine.length) stop(`you need ${kind} to do this. Carry one or wear one.`);
    const lvl = levelOf(skill);
    if (!mine.some(t => t.level <= lvl)) {
      const t = mine.reduce((a, b) => a.level <= b.level ? a : b);
      stop(`your ${t.name} needs ${cap(skill)} level ${t.level}. Yours is ${lvl}. Bring a lower one.`);
    }
  }

  // trees by tree name ("Oak", "Willow tree") or by what they give ("Maple logs")
  function treeNames(target) {
    const trees = (cat().skills || {}).woodcutting ? cat().skills.woodcutting.trees : [];
    let hit = trees.filter(t => same(t.tree, target) || same(t.tree, target + " tree") || same(t.tree + " tree", target));
    if (hit.length) hit = trees.filter(t => t.gives === hit[0].gives && t.level === hit[0].level);
    else hit = trees.filter(t => same(t.gives, target) || same(t.gives, target + " logs"));
    return {names: hit.length ? [...new Set(hit.map(t => t.tree))] : [TREES[lc(target)] || target], level: hit.length ? hit[0].level : 0,
            gives: hit.length ? hit[0].gives : "logs"};
  }

  async function chop(id, target) {
    step(id);
    const t = treeNames(target);
    if (t.level && levelOf("woodcutting") < t.level)
      stop(`you need Woodcutting level ${t.level} to cut ${target}. Yours is ${levelOf("woodcutting")}.`);
    toolCheck("woodcutting", "axes", "an axe");
    if (!free()) stop(`your inventory is full, so there's no room for more ${t.gives}.`);
    // the nearest tree the character can actually walk up to (not one behind a fence or across water)
    const pick = () => { const all = t.names.flatMap(n => objectsNamed(n, 25)).sort((a, b) => a.distance - b.distance);
      return all.slice(0, 6).find(o => can_reach(o)) || all[0] || null; };
    let tree = pick();
    if (!tree) {      // all chopped down: wait a little for one to grow back
      tree = await waitFor(pick, 30 * SECOND);
      if (!tree) stop(`there are no ${t.names[0]} trees nearby. Add a Walk To block before Chop Tree to go to some.`);
    }
    const why = __api.chopCheck(tree.x, tree.y);
    if (why) stop(why);
    if (!can_reach(tree)) await smart_move({x: tree.x, y: tree.y, z: character.z}, {within: 2}).catch(() => {});
    __api.objectActionAt(tree.x, tree.y, "Chop down");
    await idle(180 * SECOND);
    await sleep(1200);    // let the last log reach the inventory before the next check
  }

  function rockFor(target) {
    const rocks = (cat().skills || {}).mining ? cat().skills.mining.rocks : [];
    const t = String(target).replace(/\s*rocks?$/i, "");
    return rocks.find(r => same(r.ore, t) || same(r.ore, t + " ore") || (r.rock !== "Rocks" && same(r.rock, t))) || null;
  }

  async function mine(id, target) {
    step(id);
    const r = rockFor(target);
    const ore = r ? r.ore : target.replace(/\s*ore$/i, "");
    if (r && levelOf("mining") < r.level) stop(`you need Mining level ${r.level} to mine ${ore}. Yours is ${levelOf("mining")}.`);
    toolCheck("mining", "pickaxes", "a pickaxe");
    if (!free() && !(r && /essence/i.test(r.ore))) stop(`your inventory is full, so there's no room for more ${ore}.`);
    const find = () => {
      if (r && r.rock !== "Rocks") return near(objectsNamed(r.rock, 25));
      return near(get_objects("Rocks", 25).filter(x => x.ore && (same(x.ore, ore) || same(x.ore, ore + " ore"))));
    };
    let rock = find();
    if (!rock) {
      rock = await waitFor(find, 30 * SECOND);
      if (!rock) stop(`there are no ${lc(ore)} rocks nearby. Add a Walk To block before Mine Rock to go to a mine.`);
    }
    const why = __api.mineCheck(rock.x, rock.y);
    if (why) stop(why);
    __api.objectActionAt(rock.x, rock.y, "Mine");
    await idle(120 * SECOND);
    await sleep(1200);
  }

  // the fishing methods for a fish ("Lobster", "Raw trout") or a method/option ("Net", "Harpoon"), each {m, level, fish}
  function fishMethods(target, fishOnly) {
    const ms = (cat().skills || {}).fishing ? cat().skills.fishing.methods : [];
    const out = [];
    for (const m of ms) {
      const f = m.fish.find(f => same(f.name, target) || same(f.name, "Raw " + target) || same(f.name, "Raw " + target + "s")
                               || same(f.name + "s", "Raw " + target) || same(f.name, target + "s"));
      if (f) out.push({m, level: f.level, fish: f.name});
    }
    if (out.length || fishOnly) return out;
    return ms.filter(m => same(m.method, target) || same(m.option, target))
             .map(m => ({m, level: Math.min(...m.fish.map(f => f.level)), fish: m.fish[0].name}));
  }
  function hasTool(name) { return slotsWith(name).length > 0 || equipment().some(e => same(e.name, name)); }
  // the method to use: one you have the tool, bait and level for, else the first one (to say what's missing)
  function fishMethod(target, fishOnly) {
    const all = fishMethods(target, fishOnly);
    return all.find(x => hasTool(x.m.tool) && (!x.m.bait.length || x.m.bait.some(b => slotsWith(b).length)) && levelOf("fishing") >= x.level)
        || all.find(x => hasTool(x.m.tool)) || all[0] || null;
  }
  const fishName = f => lc(String(f).replace(/^Raw /i, ""));
  // in the Wilderness other players' monsters can attack; the Fishing Guild needs Fishing 68
  function wildy(p) { return p.x >= 2944 && p.x <= 3392 && p.y >= 3520 && p.y <= 3970; }
  function fishingGuild(p) { return p.x >= 2590 && p.x <= 2645 && p.y >= 3393 && p.y <= 3445; }

  // fishing spots anywhere in the world for this method that you can walk to, nearest first, one per fishing area
  function fishingSpotsFor(fm) {
    let pts = [];
    try { pts = JSON.parse(__api.fishingSpots(fm.m.method)); } catch (e) { pts = []; }
    __refresh();
    const skipped = {wildy: 0, guild: 0};
    const cost = p => Math.hypot(p[0] - character.x, p[1] - character.y) + (p[2] !== character.z ? 30 : 0) + ((p[1] >= 6400) !== (character.y >= 6400) ? 400 : 0);
    const areas = [];
    for (const p of pts.slice().sort((a, b) => cost(a) - cost(b))) {
      const q = {x: p[0], y: p[1], z: p[2]};
      if (wildy(q)) { skipped.wildy++; continue; }
      if (fishingGuild(q) && levelOf("fishing") < 68) { skipped.guild++; continue; }
      if (areas.some(a => Math.abs(a.x - q.x) < 20 && Math.abs(a.y - q.y) < 20 && a.z === q.z)) continue;
      areas.push(q);
    }
    // the easiest to get to: fewest steps on foot from here (not across the sea on an island, not round a mountain).
    // Spots on another floor or underground count by distance plus a penalty for the stairs.
    const scored = [];
    for (const a of areas.slice(0, 8)) {
      const other = (a.y >= 6400) !== (character.y >= 6400) || a.z !== character.z;
      const steps = other ? cost([a.x, a.y, a.z]) * 1.5 : __api.routeLength(a.x, a.y, 12);   // sea spots sit a few tiles off the shore
      if (steps >= 0) scored.push({a, steps});
    }
    const out = scored.sort((p, q) => p.steps - q.steps).slice(0, 4).map(s => s.a);
    out.skipped = skipped;
    out.any = pts.length;
    return out;
  }

  async function fish(id, target) {
    step(id);
    const fm = fishMethod(target);
    if (!fm && !FISH[lc(target).split(" ")[0]]) stop(`I don't know a fish or fishing method called "${target}". Pick one from the list.`);
    const option = fm ? lc(fm.m.option) : (FISH[lc(target).split(" ")[0]] || lc(target));
    if (fm) {
      const what = fishName(fm.fish);
      if (levelOf("fishing") < fm.level) stop(`you need Fishing level ${fm.level} to catch ${what}. Yours is ${levelOf("fishing")}.`);
      if (!hasTool(fm.m.tool)) stop(`you need a ${lc(fm.m.tool)} to catch ${what}. Put one in your inventory first (Withdraw it from the bank, or buy one at a fishing shop).`);
      if (fm.m.bait.length && !fm.m.bait.some(b => slotsWith(b).length))
        stop(`you need ${fm.m.bait.map(lc).join(" or ")} to catch ${what}. Put some in your inventory first.`);
    }
    if (!free()) stop("your inventory is full, so there's no room for more fish.");
    const check = s => fm ? __api.fishCheckMethod(s.index, fm.m.method) : __api.fishCheck(s.index, option);
    const look = () => {
      let best = null, firstWhy = "";
      for (const s of get_monsters("Fishing spot", 20).sort((a, b) => a.distance - b.distance)) {
        const why = check(s);
        if (!why) { best = s; break; }
        if (!firstWhy && !why.startsWith("you can't")) firstWhy = why;
      }
      return {best, firstWhy};
    };
    let r = look();
    if (!r.best && !r.firstWhy && fm) {
      // no spot for this fish here: walk to the nearest one that has it
      const what = fishName(fm.fish);
      const far = fishingSpotsFor(fm);
      if (!far.length) {
        if (far.skipped.guild) stop(`the only ${what} spots you can walk to are in the Fishing Guild, which needs Fishing level 68.`);
        if (far.skipped.wildy) stop(`the only ${what} spots are in the Wilderness, so I won't walk you there. Go there yourself, then Fish works.`);
        stop(far.any ? `every ${what} fishing spot is somewhere you can't walk to from here (an island or a quest area). Travel closer first, then Fish works.`
                     : `there are no ${what} fishing spots in this game.`);
      }
      game_log(`no ${what} spot here, so walking to the nearest one`);
      let why = "";
      for (const dest of far) {
        try { await smart_move(dest, {within: 3, loose: 10}); why = ""; }
        catch (e) { why = e.message; }
        await idle(5 * SECOND);
        await sleep(1200);      // let the last step finish, or the game drops the click on the spot
        r = look();
        if (r.best || r.firstWhy) break;
      }
      if (!r.best && !r.firstWhy) stop(why ? `could not walk to a ${what} fishing spot: ${why}.` : `the ${what} spots have moved away. Run the script again.`);
    }
    if (!r.best) stop(r.firstWhy || `there is no fishing spot nearby where you can ${option}. Add a Walk To block before Fish to go to one.`);
    __api.npcAction(r.best.index, cap(option));
    await idle(300 * SECOND);
    await sleep(1200);
  }

  // Light Fire: tinderbox on logs, stepping aside when the ground there won't take a fire
  async function light_fire(id, logs) {
    step(id);
    const name = logs && lc(logs) !== "any logs" ? logs : (inv().find(i => /logs$/i.test(i.name)) || {}).name;
    if (!name || !slotsWith(name).length) stop(logs && lc(logs) !== "any logs" ? `you have no ${logs} to light.` : "you have no logs to light.");
    const row = ((cat().skills || {}).firemaking ? cat().skills.firemaking.logs : []).find(l => same(l.log, name));
    if (row && levelOf("firemaking") < row.level) stop(`you need Firemaking level ${row.level} to light ${name}. Yours is ${levelOf("firemaking")}.`);
    if (!slotsWith("Tinderbox").length) stop("you need a tinderbox to light a fire.");
    for (let tries = 0; tries < 4; tries++) {
      const before = slotsWith(name).length;
      __api.useSlotOnSlot(slotsWith("Tinderbox")[0] - 1, slotsWith(name)[0] - 1);
      await sleep(1200);
      await idle(30 * SECOND);
      await sleep(1200);
      if (slotsWith(name).length < before) return;
      __refresh();    // fire can't go here (a fire, a bank booth or a door is in the way): step aside and try again
      const steps = [[1, 0], [0, 1], [-1, 0], [0, -1]][tries];
      await smart_move({x: character.x + steps[0], y: character.y + steps[1], z: character.z}, {within: 0}).catch(() => {});
    }
    stop(`couldn't find a spot to light the ${name}. Move to open ground first.`);
  }

  // Bury Bones: buries every one of that kind (or any bones)
  async function bury_bones(id, bones) {
    step(id);
    const isBones = i => bones && lc(bones) !== "any bones" ? same(i.name, bones) : /bones$/i.test(i.name);
    if (!inv().some(isBones)) stop(bones && lc(bones) !== "any bones" ? `you have no ${bones} to bury.` : "you have no bones to bury.");
    for (let i = 0; i < 28; i++) {
      const b = inv().find(isBones);
      if (!b) break;
      __api.itemAction(b.name, "Bury");
      await sleep(1200);
    }
  }

  async function attack_monster(id, target) {
    step(id);
    __refresh();
    if (character.hp * 100 / character.max_hp < 20)
      stop(`your HP is too low to fight (${character.hp}/${character.max_hp}). Add an Eat block or wait to heal first.`);
    let m = get_nearest_monster({name: target, radius: 25});
    if (!m) {
      m = await waitFor(() => get_nearest_monster({name: target, radius: 25}), 15 * SECOND);
      if (!m) stop(`there is no ${target} nearby to attack. Add a Walk To block to go where they are.`);
    }
    await fight(m, target);
  }

  async function fight(m, target) {
    for (let tries = 0; tries < 6; tries++) {
      const r = attack(m);
      if (r !== "door") break;
      await idle(30 * SECOND);
    }
    const index = m.index;
    const done = await waitFor(() => {
      const still = get_monsters("", 30).filter(x => x.index === index && x.hp > 0);
      __refresh();
      if (!still.length) return "dead";
      if (!character.in_combat && !character.moving && !still[0].in_combat && __tickNo % 5 === 0) attack(still[0]);
      return false;
    }, 180 * SECOND);
    if (!done) stop(`could not finish the fight with the ${target}.`);
    await sleep(1200);    // let the drop land
  }

  async function pick_up(id, target) {
    step(id);
    if (!free() && !has(target)) stop(`your inventory is full, so you can't pick up the ${target}.`);
    const found = await waitFor(() => ground_items(10).find(g => lc(g.name) === lc(target)), 3 * SECOND);
    if (!found) { log(`no ${target} on the ground nearby`); return; }
    loot([target]);
    await idle(30 * SECOND);
  }

  async function eat_food(id, target) {
    step(id);
    let food = (!target || lc(target) === "any food") ? __api.foodName() : target;
    if (!food || !has(food)) stop(target && lc(target) !== "any food" ? `you have no ${target} left to eat.` : "you have no food left to eat.");
    eat(food);
    await sleep(1800);
  }

  async function open_bank(id) {
    step(id);
    if (__api.bankOpen()) return;
    if (__api.openBank() === "no-bank") stop("there is no bank nearby. Add a Walk To block to go to a bank first, for example Walk To → Draynor Bank.");
    const ok = await waitFor(() => __api.bankOpen(), 30 * SECOND);
    if (!ok) stop("could not open the bank. Is the way to the bank booth blocked?");
  }

  function needBank() { if (!__api.bankOpen()) stop("the bank is not open. Add an Open Bank block first."); }

  // target: "Inventory All", "Inventory 5", "Inventory 3-22" or an item name
  function slotsOf(target) {
    const t = String(target || "").replace(/[–—]/g, "-").replace(/^inventory\s*/i, "").trim();
    if (!t || lc(t) === "all") return {from: 1, to: 28};
    const m = t.match(/^(?:slots?\s*)?(\d+)\s*(?:-\s*(\d+))?$/i);
    if (m) return {from: +m[1], to: +(m[2] || m[1])};
    return null;
  }

  async function deposit(id, target) {
    step(id);
    needBank();
    const s = slotsOf(target);
    const n = s ? __api.depositSlots(s.from, s.to) : __api.depositItem(target);
    if (n < 0) stop("your bank is full.");
    if (n === 0 && !s && !has(target)) log(`no ${target} to deposit`);
    await sleep(600);
  }

  async function withdraw(id, target, amount) {
    step(id);
    needBank();
    const r = __api.withdrawItem(target, amount || 0);
    if (r === "not-in-bank") stop(`there is no ${target} in your bank.`);
    if (r === "inventory-full") stop(`your inventory is full, so you can't take out the ${target}.`);
    await sleep(600);
  }

  async function close_bank(id) { step(id); __api.closeBank(); await sleep(600); }

  async function drop(id, target) {
    step(id);
    const s = slotsOf(target);
    __api.drop(s ? "" : target, s ? s.from : 1, s ? s.to : 28);
    await sleep(600);
  }

  async function wait(id, seconds) { step(id); await sleep(Math.max(0.6, +seconds || 1) * SECOND); }

  function say_text(id, text) { step(id); say(text); }

  // ---- using items ----
  // what the inventory looks like, to notice when a use did something
  function snapshot() { return inv().map(i => i.slot + ":" + i.id + "x" + i.amount).join(","); }
  function inputOpen() { return chatbox().kind === "input"; }

  // after a use: pick from a make menu if one opens, then wait for the work to finish
  async function afterUse(before, product, amount, what, mustChange, hadInput) {
    const first = await waitFor(() => {
      const k = chatbox().kind;
      if (k !== "none" && !(k === "input" && hadInput)) return k;
      __refresh();
      return (snapshot() !== before || character.busy || character.moving) ? "work" : false;
    }, 4 * SECOND);
    if (!first) stop(`nothing happened when you used ${what}. That doesn't do anything in this game.`);
    // walk there, pick from the "how many" menu when it opens, and wait until the work stops
    let chosen = false, quiet = 0, problem = "";
    await waitFor(() => {
      const k = chatbox().kind;
      if (k === "make" && !chosen) {
        chosen = true; quiet = 0;
        const r = __api.makeChoice(product || "", amount || 0);
        if (r.startsWith("not-in-menu:")) { problem = `${what} offers ${r.slice(12)}, not ${product}.`; return true; }
        return false;
      }
      if ((k === "input" && !hadInput) || k === "options" || k === "continue") return true;    // the next block answers it
      __refresh();
      if (character.busy || character.moving) { quiet = 0; return false; }
      return ++quiet >= 3;
    }, 300 * SECOND);
    if (problem) stop(problem);
    await sleep(600);
    if (mustChange && !chosen && first === "work" && snapshot() === before)
      stop(`nothing happened when you used ${what}. That doesn't do anything in this game.`);
  }

  function needItem(name) {
    const s = slotsWith(name);
    if (!s.length) stop(`you have no ${name} in your inventory.`);
    return s[0];
  }

  function useResult(r, what) {
    if (r === "busy") stop(`your character is busy and can't use ${what} right now.`);
    if (r === "no-item") stop(`that inventory slot is empty.`);
    if (r === "same-slot") stop(`you can't use an item on itself.`);
  }

  // Use Item on Item: "Clean guam" on "Vial of water", making All (0) or a number
  async function use_item_on_item(id, a, b, amount, product) {
    step(id);
    const sa = needItem(a), sb = needItem(b);
    let rs = recipeFor(a, b);
    if (product) rs = rs.filter(r => same(r.makes, product) || norm(r.makes).replace(/\d+$/, "") === norm(product));
    const why = cantMake(rs);
    if (why) stop(why);
    const before = snapshot(), hadInput = inputOpen();
    useResult(__api.useSlotOnSlot(sa - 1, sb - 1), a);
    await afterUse(before, rs.length === 1 ? rs[0].makes : "", +amount || 0, `${a} on ${b}`, true, hadInput);
  }

  // Use Slot on Slot: inventory slots 1-28
  async function use_slot_on_slot(id, a, b, amount) {
    step(id);
    const x = itemInSlot(+a), y = itemInSlot(+b);
    if (!x) stop(`inventory slot ${a} is empty.`);
    if (!y) stop(`inventory slot ${b} is empty.`);
    const rs = recipeFor(x.name, y.name);
    const why = cantMake(rs);
    if (why) stop(why);
    const before = snapshot(), hadInput = inputOpen();
    useResult(__api.useSlotOnSlot(+a - 1, +b - 1), x.name);
    await afterUse(before, rs.length === 1 ? rs[0].makes : "", +amount || 0, `${x.name} on ${y.name}`, true, hadInput);
  }

  // the nearest thing called `target`: an object, else a person or monster, else an item on the ground
  // exact name first, then any name containing it ("Range" finds a "Cooking range")
  function named(list, target) {
    const exact = list.filter(o => same(o.name, target));
    return exact.length ? exact : list.filter(o => norm(o.name).includes(norm(target)));
  }
  function findTarget(target) {
    if (isPatchWord(target)) { const p = findPatch(target); if (p) return {kind: "object", x: p.x, y: p.y, name: p.name}; }
    const objs = named(get_objects("", 25), target).sort((a, b) => a.distance - b.distance);
    const obj = objs.slice(0, 6).find(o => can_reach(o)) || objs[0];
    if (obj) return {kind: "object", x: obj.x, y: obj.y, name: obj.name};
    const npc = near(named(get_monsters("", 25), target));
    if (npc) return {kind: "npc", index: npc.index, name: npc.name};
    const g = near(named(ground_items(15), target));
    if (g) return {kind: "ground", name: g.name};
    return null;
  }

  async function useSlotOnTarget(slot, itemName, target, amount, product) {
    let t = findTarget(target);
    if (!t) {
      t = await waitFor(() => findTarget(target), 5 * SECOND);
      if (!t) stop(`there is no ${target} nearby. Add a Walk To block to go to one first.`);
    }
    let rs = (cat().recipes || []).filter(r => same(r.use, itemName) && (same(r.on, target) || same(r.on, t.name)));
    if (product) rs = rs.filter(r => same(r.makes, product));
    const why = cantMake(rs);
    if (why) stop(why);
    const before = snapshot(), hadInput = inputOpen();
    const r = t.kind === "object" ? __api.useSlotOnObjectAt(slot - 1, t.x, t.y)
            : t.kind === "npc" ? __api.useSlotOnNpc(slot - 1, t.index)
            : __api.useSlotOnGround(slot - 1, t.name);
    useResult(r, itemName);
    await afterUse(before, rs.length === 1 ? rs[0].makes : "", +amount || 0, `${itemName} on the ${t.name}`, false, hadInput);
  }

  // Use Item on Target: an object (furnace, range, farming patch), an NPC or an item on the ground
  async function use_item_on_target(id, itemName, target, amount, product) {
    step(id);
    await useSlotOnTarget(needItem(itemName), itemName, target, amount, product);
  }

  // Use Inventory Slot on Target: "Slot 4" on "Farming patch"
  async function use_slot_on_target(id, slot, target, amount) {
    step(id);
    const n = +String(slot).replace(/\D/g, "");
    const it = itemInSlot(n);
    if (!it) stop(`inventory slot ${n} is empty.`);
    await useSlotOnTarget(n, it.name, target, amount);
  }

  // ---- dialogue and keys ----
  function chatbox() { return JSON.parse(__api.chatbox()); }

  async function talk_to(id, target) {
    step(id);
    let npc = near(get_monsters(target, 25));
    if (!npc) {
      npc = await waitFor(() => near(get_monsters(target, 25)), 5 * SECOND);
      if (!npc) stop(`there is no one called ${target} nearby. Add a Walk To block to go to them first.`);
    }
    if (!__api.npcAction(npc.index, "Talk-to")) stop(`you can't talk to the ${npc.name}.`);
    const open = await waitFor(() => chatbox().kind !== "none", 20 * SECOND);
    if (!open) stop(`the ${npc.name} didn't answer. Maybe the way to them is blocked.`);
  }

  // Press Key: Space or Enter continues, 1-5 picks an option, Escape closes. Several keys can be listed: "Space, Space, 2"
  async function press_key(id, keys) {
    step(id);
    const list = String(keys || "space").split(/[,\s]+/).filter(k => k);
    for (const k of list) {
      const open = await waitFor(() => chatbox().kind !== "none" || lc(k).startsWith("esc"), 5 * SECOND);
      if (!open) stop(`there is nothing on screen to press ${cap(k)} for. Add a Talk To block first.`);
      const r = __api.pressKey(k);
      if (r === "no-options") stop(`there are no numbered options on screen to press ${k} for.`);
      if (r.startsWith("only-")) stop(`there are only ${r.slice(5)} options on screen, so ${k} can't be picked.`);
      if (r === "choose-option") stop(`the screen is asking you to pick an option. Press a number key instead of ${cap(k)}.`);
      if (r === "make-menu") stop(`a "how many" menu is open. Use a Make block to choose what to make.`);
      if (r === "unknown-key") stop(`${k} is not a key I can press. Use Space, Enter, Escape or 1 to 5.`);
      await sleep(900);
    }
  }

  // Wait For: "dialogue", "options", "make menu", "input box", "no dialogue"
  async function wait_for(id, what, seconds) {
    step(id);
    const want = norm(what);
    const test = () => {
      const k = chatbox().kind;
      if (want === "nodialogue" || want === "dialogueclosed") return k === "none";
      if (want === "options") return k === "options";
      if (want === "makemenu") return k === "make";
      if (want === "inputbox") return k === "input";
      return k !== "none";
    };
    if (!(await waitFor(test, (+seconds || 10) * SECOND))) stop(`waited ${+seconds || 10} seconds but no ${lc(what)} appeared.`);
  }

  // Make: choose a product in the "how many?" menu
  async function make(id, product, amount) {
    step(id);
    const m = await waitFor(() => chatbox().kind === "make", 5 * SECOND);
    if (!m) stop(`no "how many" menu is open. Use an item on another first.`);
    const r = __api.makeChoice(product || "", +amount || 0);
    if (r.startsWith("not-in-menu:")) stop(`this menu offers ${r.slice(12)}, not ${product}.`);
    await sleep(1200);
    await idle(300 * SECOND);
  }

  // Make Item: the game works out how. A "how many" menu, a station's full-screen menu
  // (furnace, anvil, spinning wheel, tanner) or a known "use X on Y" recipe.
  async function make_item(id, product, amount) {
    step(id);
    if (chatbox().kind === "make") {
      const r = __api.makeChoice(product || "", +amount || 0);
      if (r.startsWith("not-in-menu:")) stop(`this menu offers ${r.slice(12)}, not ${product}.`);
      await sleep(1200); await idle(300 * SECOND); return;
    }
    const before = snapshot();    // tanning happens at once, so look before asking
    let r = __api.craft(product, +amount || 0);
    if (r.startsWith("station:")) {
      const station = r.slice(8);
      const t = findTarget(station);
      if (!t) stop(`you need to be at ${/^[aeiou]/i.test(station) ? "an" : "a"} ${lc(station)} to make ${product}. Add a Walk To block to go to one first.`);
      if (t.kind === "object") await smart_move({x: t.x, y: t.y, z: character.z}, {within: 1}).catch(() => {});
      else { const m = get_monsters(t.name, 25).find(x => x.index === t.index); if (m) await smart_move({x: m.x, y: m.y, z: m.z}, {within: 2}).catch(() => {}); }
      r = __api.craft(product, +amount || 0);
      if (r.startsWith("station:")) stop(`couldn't get next to the ${lc(station)} to make ${product}.`);
    }
    if (r.startsWith("level:")) { const [, skill, need, have] = r.split(":"); stop(`you need ${cap(lc(skill))} level ${need} to make ${product}. Yours is ${have}.`); }
    if (r.startsWith("no-materials:")) stop(`you have no ${r.slice(13)} to make ${product} from.`);
    if (r.startsWith("ok")) {
      await sleep(1800);
      await idle(600 * SECOND);
      await sleep(600);
      if (snapshot() === before) stop(`couldn't make ${product}. Check the game chat for what's missing.`);
      return;
    }
    // a "use X on Y" recipe from the catalogue
    let recipes = (cat().recipes || []).filter(x => same(x.makes, product));
    // "Attack potion" means "Attack potion(3)"
    if (!recipes.length) recipes = (cat().recipes || []).filter(x => norm(x.makes).replace(/\d+$/, "") === norm(product));
    if (!recipes.length) stop(`I don't know how to make ${product}. Check the name in the catalogue.`);
    const why = cantMake(recipes);
    if (why) stop(why);
    const rec = recipes.find(x => slotsWith(x.use).length && slotsWith(x.on).length)
             || recipes.find(x => slotsWith(x.use).length && x.station);
    if (!rec) stop(`to make ${product} you need ${recipes[0].use} and ${recipes[0].on}.`);
    if (slotsWith(rec.on).length) await use_item_on_item(id, rec.use, rec.on, amount, rec.makes);
    else await use_item_on_target(id, rec.use, rec.on, amount, rec.makes);
  }

  // Click Object: any option on the nearest object with that name ("Open" a door, "Climb-up" a ladder)
  async function click_object(id, target, option) {
    step(id);
    const objs = named(get_objects("", 25), target).sort((a, b) => a.distance - b.distance);
    if (!objs.length) stop(`there is no ${target} nearby. Add a Walk To block to go to one first.`);
    const r = __api.objectAction(objs[0].name, option);
    if (r === "no-object") stop(`the ${objs[0].name} nearby has no "${option}" option.`);
    await sleep(1200);
    await idle(120 * SECOND);
  }

  // Click NPC: any option on the nearest person or creature with that name ("Pickpocket", "Trade")
  async function click_npc(id, target, option) {
    step(id);
    let m = near(named(get_monsters("", 25), target));
    if (!m) {
      m = await waitFor(() => near(named(get_monsters("", 25), target)), 5 * SECOND);
      if (!m) stop(`there is no ${target} nearby. Add a Walk To block to go to them first.`);
    }
    if (!__api.npcAction(m.index, option)) stop(`you can't ${lc(option)} the ${m.name}.`);
    await sleep(1200);
    await idle(120 * SECOND);
  }

  // Craft Runes: enters the ruins with a talisman or tiara if needed, then crafts at the altar
  async function craft_runes(id) {
    step(id);
    const ess = inv().filter(i => /^(rune|pure) essence$/i.test(i.name));
    if (!ess.length) stop("you have no rune essence to craft with.");
    const altarNear = () => named(get_objects("", 20), "Altar").length && __api.objectAction("Altar", "Craft-rune") !== "no-object";
    if (!altarNear()) {
      const ruins = near(named(get_objects("", 25), "Mysterious ruins"));
      if (!ruins) stop("you're not at an altar or its ruins. Add a Walk To block to go to one, for example Walk To → Air altar.");
      const rc = (cat().skills || {}).runecrafting || {};
      const tiara = (rc.ruins || []).find(r => equipment().some(e => same(e.name, r.tiara)));
      const tali = (rc.ruins || []).find(r => slotsWith(r.talisman).length);
      if (__api.questState("Rune Mysteries") !== "done") stop("you must complete the Rune Mysteries quest before you can enter the ruins.");
      const which = lc((tiara || tali || {}).altar || "");
      const extra = {"cosmic altar": "Lost City", "death altar": "Mourning's End Part II", "blood altar": "Legacy of Seergaze", "astral altar": "Lunar Diplomacy"}[which];
      if (extra && __api.questState(extra) !== "done") stop(`you must complete the ${extra} quest before you can enter the ${which}.`);
      __refresh(); const from = character.x + "," + character.y;
      if (tiara) __api.objectAction("Mysterious ruins", "Enter");
      else if (tali) __api.useSlotOnObjectAt(slotsWith(tali.talisman)[0] - 1, ruins.x, ruins.y);
      else stop("you need the right talisman or tiara to enter these ruins.");
      const inside = await waitFor(() => { __refresh(); return character.x + "," + character.y !== from && !character.moving && named(get_objects("", 20), "Altar").length; }, 30 * SECOND);
      if (!inside) stop("couldn't enter the ruins. Is it the right talisman for this altar?");
      await sleep(1200);
      if (!altarNear()) stop("couldn't reach the altar inside the ruins.");
    }
    const before = ess.length;
    await waitFor(() => inv().filter(i => /^(rune|pure) essence$/i.test(i.name)).length < before, 20 * SECOND);
    await sleep(1200);
    if (inv().filter(i => /^(rune|pure) essence$/i.test(i.name)).length >= before)
      stop("the altar didn't take your essence. Check the game chat (your Runecrafting level may be too low).");
  }

  // ---- Hunter ----
  function traps() { return JSON.parse(__api.myTraps()); }
  function trapCount() { return JSON.parse(__api.trapCount()); }
  function hunterRow(creature) {
    const h = (cat().skills || {}).hunter || {};
    return (h.traps || []).find(t => same(t.creature, creature) || same(t.creature + "s", creature)) || null;
  }

  // Lay Trap: bird snare, box trap or rabbit snare from the inventory; net trap on a young tree; deadfall on its rock
  async function lay_trap(id, trap) {
    step(id);
    const t = lc(trap);
    const objectTrap = t.includes("net") ? "Young tree" : t.includes("deadfall") ? "" : null;
    if (objectTrap === null && !slotsWith(trap).length) stop(`you have no ${trap} to lay.`);
    if (t.includes("net") && (!slotsWith("Rope").length || !slotsWith("Small fishing net").length)) stop("a net trap needs a rope and a small fishing net.");
    const c = trapCount();
    if (c.laid >= c.max) stop(`you already have ${c.max} traps out, the most your Hunter level allows.`);
    for (let tries = 0; tries < 4; tries++) {
      if (objectTrap === null) __api.itemAction(slotsWith(trap).length ? inv().find(i => same(i.name, trap)).name : trap, "Lay");
      else {
        const spots = get_objects(objectTrap, 20).filter(o => o.options.some(x => lc(x) === "set-trap")).sort((a, b) => a.distance - b.distance);
        if (!spots.length) stop(`there's nowhere to set a ${lc(trap)} here. Go to where the creatures you want are.`);
        __api.objectActionAt(spots[0].x, spots[0].y, "Set-trap");
      }
      const ok = await waitFor(() => trapCount().laid > c.laid, 8 * SECOND);
      await idle(10 * SECOND);
      if (ok) return;
      if (objectTrap !== null) break;
      __refresh();    // can't lay here (another trap or an object): step aside and try again
      const st = [[1, 0], [0, 1], [-1, 0], [0, -1]][tries];
      await smart_move({x: character.x + st[0], y: character.y + st[1], z: character.z}, {within: 0}).catch(() => {});
    }
    stop(`couldn't lay the ${lc(trap)}. Check the game chat (a level or a quest may be needed).`);
  }

  // Check Traps: collects catches, resets collapsed traps and picks up traps lying on the ground
  async function check_traps(id) {
    step(id);
    for (const w of traps()) {
      if (w.busy) continue;
      if (w.caught) __api.objectActionAt(w.x, w.y, "Check");
      else if (w.failed) __api.objectActionAt(w.x, w.y, "Dismantle");
      else continue;
      await sleep(1200);
      await idle(20 * SECOND);
    }
    for (const g of ground_items(10).filter(g => /^(bird snare|box trap|rabbit snare)$/i.test(g.name))) {
      if (!free()) break;
      loot([g.name]);
      await idle(10 * SECOND);
    }
  }

  // Hunt: lays as many traps as allowed for this creature, waits for a catch, collects it
  async function hunt(id, creature) {
    step(id);
    const row = hunterRow(creature);
    if (!row) stop(`I don't know how to trap ${creature}. Pick a creature from the Hunter list.`);
    if (levelOf("hunter") < row.level) stop(`you need Hunter level ${row.level} to catch ${row.creature}. Yours is ${levelOf("hunter")}.`);
    if (!free()) stop(`your inventory is full, so there's no room for what the ${row.creature} drops.`);
    const item = row.item || row.trap;
    while (trapCount().laid < trapCount().max && (slotsWith(item).length || /net|deadfall/i.test(row.trap))) {
      const before = trapCount().laid;
      await lay_trap(id, row.trap);
      if (trapCount().laid <= before) break;
      const mine = traps(); const last = mine[mine.length - 1];
      if (row.bait && row.bait.length && last) {    // bait helps box traps
        const b = row.bait.find(x => slotsWith(x).length);
        if (b) { __api.useSlotOnObjectAt(slotsWith(b)[0] - 1, last.x, last.y); await sleep(1800); }
      }
      __refresh();
      await smart_move({x: character.x + 2, y: character.y, z: character.z}, {within: 1}).catch(() => {});
    }
    if (!trapCount().laid) stop(`you have no ${lc(item)} to set.`);
    const done = await waitFor(() => traps().some(w => (w.caught || w.failed) && !w.busy), 180 * SECOND);
    if (!done) return;
    await check_traps(id);
  }

  // Track Kebbit: inspects the burrow, follows the tracks and catches the kebbit at the end (needs a noose wand)
  async function track(id) {
    step(id);
    if (!slotsWith("Noose wand").length && !equipment().some(e => same(e.name, "Noose wand"))) stop("you need a noose wand to catch a tracked kebbit.");
    const closeDialogue = async () => { for (let i = 0; i < 4 && chatbox().kind === "continue"; i++) { __api.pressKey("space"); await sleep(600); } };
    let next = JSON.parse(__api.trackNext());
    if (next.state === "none") {
      const start = get_objects("", 20).filter(o => o.options.some(x => lc(x) === "inspect")).sort((a, b) => a.distance - b.distance)[0];
      if (!start) stop("there's no burrow or tracks to inspect here. Go to a kebbit hunting area first.");
      __api.objectActionAt(start.x, start.y, "Inspect");
      await waitFor(() => JSON.parse(__api.trackNext()).state !== "none" || chatbox().kind !== "none", 20 * SECOND);
      await closeDialogue();
      next = JSON.parse(__api.trackNext());
      if (next.state === "none") stop("that wasn't the start of a trail. Try another burrow.");
    }
    for (let steps = 0; steps < 12 && next.state === "next"; steps++) {
      const o = get_objects("", 25).find(x => x.x === next.x && x.y === next.y);
      const opt = o && o.options.find(x => /inspect|search/i.test(x)) || "Inspect";
      __api.objectActionAt(next.x, next.y, opt);
      const before = next.x + "," + next.y;
      await waitFor(() => { const n = JSON.parse(__api.trackNext()); return n.x + "," + n.y !== before || n.state !== "next"; }, 20 * SECOND);
      await closeDialogue();
      next = JSON.parse(__api.trackNext());
    }
    if (next.state !== "end") stop("lost the trail. Try again from the burrow.");
    __api.objectActionAt(next.x, next.y, "Attack");
    await sleep(1800);
    await idle(20 * SECOND);
    await closeDialogue();
  }

  // ---- Farming ----
  // one entry per patch (a patch covers many tiles), the nearest tile of each
  function patches(radius) {
    const best = {};
    for (const p of JSON.parse(__api.patches(radius || 20))) if (!best[p.patch] || p.distance < best[p.patch].distance) best[p.patch] = p;
    return Object.values(best);
  }
  function isPatchWord(t) { return /patch|allotment/i.test(t); }
  // the nearest patch matching "Allotment", "Herb patch", a patch's own name, or any patch for "Farming patch"
  function findPatch(target) {
    const all = patches(20).sort((a, b) => a.distance - b.distance);
    if (!target || /^(farming )?patch$/i.test(String(target).trim())) return all[0] || null;
    return all.find(p => same(p.name, target) || same(p.patch, target) || same(p.type, target) || same(p.type + " patch", target)
      || norm(p.name).includes(norm(target)) || norm(p.patch).includes(norm(target))) || null;
  }
  function needPatch(target) {
    const p = findPatch(target);
    if (!p) stop(`there's no ${target || "farming patch"} nearby. Add a Walk To block to go to some farming patches first.`);
    return p;
  }
  async function rake_patch(id, target) {
    step(id);
    let p = needPatch(target);
    if (p.state !== "weeds") return;
    if (!slotsWith("Rake").length) stop("you need a rake to clear the weeds.");
    for (let i = 0; i < 6 && p.state === "weeds"; i++) {
      if (!free()) stop("your inventory is full of weeds. Drop them or add a Drop block.");
      __api.useSlotOnObjectAt(slotsWith("Rake")[0] - 1, p.x, p.y);    // in this game you use the rake on the patch
      await sleep(1800);
      await idle(120 * SECOND);
      p = findPatch(p.patch) || p;
    }
  }

  // Plant: a seed with a seed dibber, or a sapling with a spade
  async function plant(id, seed, target) {
    step(id);
    let p = needPatch(target);
    if (p.state === "weeds") await rake_patch(id, p.patch), p = findPatch(p.patch);
    if (p.state !== "empty") stop(`the ${p.name} already has something in it (${p.plant || p.state}).`);
    const rows = ((cat().skills || {}).farming || {}).seeds || [];
    const row = rows.find(r => same(r.seed, seed));
    if (row && levelOf("farming") < row.level) stop(`you need Farming level ${row.level} to plant ${seed}. Yours is ${levelOf("farming")}.`);
    if (!slotsWith(seed).length) stop(`you have no ${seed} to plant.`);
    const sapling = /sapling/i.test(seed);
    if (!sapling && !slotsWith("Seed dibber").length) stop("you need a seed dibber to plant seeds.");
    if (sapling && !slotsWith("Spade").length) stop("you need a spade to plant a sapling.");
    const before = slotsWith(seed).length + inv().filter(i => same(i.name, seed)).reduce((a, i) => a + i.amount, 0);
    __api.useSlotOnObjectAt(slotsWith(seed)[0] - 1, p.x, p.y);
    await sleep(1800);
    await idle(30 * SECOND);
    const after = findPatch(p.patch);
    if (!after || after.state === "empty") stop(`couldn't plant the ${seed} here. Is it the right kind of patch? (${row ? row.patch : "check the seed"})`);
  }

  async function water_patch(id, target) {
    step(id);
    const p = needPatch(target);
    const can = inv().find(i => /^watering can\(\d\)$/i.test(i.name));
    if (!can) stop("you need a watering can with water in it.");
    if (p.watered || p.state !== "growing") return;
    __api.useSlotOnObjectAt(can.slot, p.x, p.y);
    await sleep(1800); await idle(10 * SECOND);
  }

  async function compost_patch(id, target) {
    step(id);
    const p = needPatch(target);
    const c = inv().find(i => /^(super)?compost$/i.test(i.name));
    if (!c) stop("you have no compost or supercompost.");
    if (p.compost !== "none") return;
    if (p.state === "weeds") stop(`rake the ${p.name} first. Compost goes on an empty, weeded patch.`);
    if (p.state !== "empty") stop(`compost goes on before planting. The ${p.name} already has ${p.plant || "something"} in it.`);
    __api.useSlotOnObjectAt(c.slot, p.x, p.y);
    await sleep(1800); await idle(10 * SECOND);
  }

  async function cure_patch(id, target) {
    step(id);
    const p = needPatch(target);
    if (p.state !== "diseased") return;
    if (!slotsWith("Plant cure").length) stop("this patch is diseased and you have no plant cure.");
    __api.useSlotOnObjectAt(slotsWith("Plant cure")[0] - 1, p.x, p.y);
    await sleep(1800); await idle(10 * SECOND);
  }

  // Harvest: picks, harvests, checks a tree's health or chops a grown tree; clears a dead patch
  async function harvest_patch(id, target) {
    step(id);
    const p = needPatch(target);
    if (p.state === "dead") { if (!slotsWith("Spade").length) stop("this patch died and you need a spade to clear it.");
      __api.useSlotOnObjectAt(slotsWith("Spade")[0] - 1, p.x, p.y); await sleep(1800); await idle(30 * SECOND); return; }
    if (p.state !== "grown" && !p.check_health) stop(`the ${(p.plant || p.name).replace(/ seed$|sapling$/i, "").trim()} isn't ready yet.`);
    if (!free()) stop("your inventory is full, so there's no room for the harvest.");
    const o = get_objects("", 25).find(x => x.x === p.x && x.y === p.y);
    const opt = (o ? o.options : []).find(x => /check-health|harvest|pick|chop/i.test(x));
    if (!opt) stop(`there's nothing to harvest on the ${p.name} right now.`);
    if (/harvest|pick/i.test(opt) && /allotment|herb|flower|hops/i.test(p.type) && !slotsWith("Spade").length && /allotment|hops/i.test(p.type))
      stop("you need a spade to harvest this patch.");
    __api.objectActionAt(p.x, p.y, opt);
    await sleep(1800);
    await idle(180 * SECOND);
  }


  // ---- Agility ----
  function courseRow(name) {
    const cs = ((cat().skills || {}).agility || {}).courses || [];
    return cs.find(c => same(c.course, name) || same(c.course, name + " course") || norm(c.course).startsWith(norm(name))) || null;
  }
  // the nearest object for one obstacle of a lap: by id (any of several) or by name, with that option.
  // `used` holds the spots already crossed this lap, so a row of the same wall is taken one after another
  function obstacle(o, used) {
    const all = get_objects("", 25).filter(x => (o.ids ? o.ids.includes(x.id) || o.ids.includes(x.shown_id) : same(x.name, o.name))
      && x.options.some(op => same(op, o.option)));
    const fresh = all.filter(x => !(used || new Set()).has(x.x + "," + x.y));
    return near(fresh.length ? fresh : all);
  }
  async function settle(ms) {    // until the obstacle animation and its walking are over
    await waitFor(() => { __refresh(); return !character.locked && !character.cant_move && !character.moving && !character.busy; }, ms);
  }
  function here() { __refresh(); return character.x + "," + character.y + "," + character.z; }

  // Run Agility Course: goes over every obstacle of the course in order, `laps` times
  async function run_course(id, course, laps) {
    step(id);
    const c = courseRow(course);
    if (!c) stop(`I don't know the ${course}. Pick one from the Agility list.`);
    if (levelOf("agility") < c.level) stop(`you need Agility level ${c.level} for the ${c.course}. Yours is ${levelOf("agility")}.`);
    if (!c.laps) stop(`${c.note || "this course can't be run by one block."}`);
    // outside the course: go in first (the Barbarian pipe, the Wilderness gate)
    const first = obstacle(c.laps[0]);
    if (c.enter && (!first || !can_reach(first))) {
      const door = obstacle(c.enter);
      if (!door) stop(`you're not at the ${c.course}. Add a Walk To → ${c.course} block first.`);
      const from = here();
      __api.objectActionAt(door.x, door.y, c.enter.option);
      const moved = await waitFor(() => here() !== from && !character.locked && !character.moving, 30 * SECOND);
      await settle(10 * SECOND);
      if (!moved) stop(`couldn't get into the ${c.course}. Check the game chat.`);
    }
    const n = Math.max(1, +laps || 1);
    if (c.laps[0].at) return run_spots(c, n);
    for (let lap = 0; lap < n; lap++) {
      const used = new Set();
      for (let i = 0; i < c.laps.length; i++) {
        const o = c.laps[i];
        let ob = obstacle(o, used);
        if (!ob) ob = await waitFor(() => obstacle(o, used), 5 * SECOND);
        if (!ob) {
          if (lap === 0 && i === 0) stop(`you're not at the ${c.course}. Add a Walk To → ${c.course} block first.`);
          stop(`lost the course before "${o.option}". Walk back to the start and run the block again.`);
        }
        for (let tries = 0; ; tries++) {
          __refresh(); const xp0 = character.xp.agility, hp0 = character.hp, z0 = character.z;
          __api.objectActionAt(ob.x, ob.y, o.option);
          // obstacles give Agility XP when done (a ladder just changes floor); a slip costs HP instead
          const r = await waitFor(() => { __refresh(); return character.xp.agility > xp0 || (character.z !== z0 && !character.locked) ? "ok" : character.hp < hp0 ? "fell" : null; }, 30 * SECOND);
          await settle(10 * SECOND);
          if (r === "ok") break;
          __refresh();
          if (r === "fell") {
            if (character.hp * 100 / character.max_hp < 20) stop(`you slipped at the ${ob.name} and your HP is low (${character.hp}/${character.max_hp}). Eat before going on.`);
            const again = obstacle(o, used);
            if (!again || !can_reach(again) || tries >= 2) stop(`you slipped at the ${ob.name} and fell off the course. Walk back to the start and run the block again.`);
            ob = again;
            continue;
          }
          if (tries >= 2) stop(`couldn't get past the ${ob.name}. Check the game chat.`);
        }
        used.add(ob.x + "," + ob.y);
        await sleep(600);
      }
    }
  }

  // A course given obstacle by obstacle with where each one is (the Agility Pyramid): stand on the spot it is started
  // from, use it, check it worked. A slip drops you a floor: carry on from that floor. `top` is the pyramid top.
  async function run_spots(c, n) {
    const L = c.laps;
    const onFloor = o => { __refresh(); return character.z === o.at[2] && Math.hypot(character.x - o.at[0], character.y - o.at[1]) < 40; };
    const objAt = o => get_objects("", 40).find(x => x.x === o.at[0] && x.y === o.at[1] && x.options.some(op => same(op, o.option))) || null;
    // where to carry on: the furthest obstacle on this floor whose starting spot you can walk to
    const resume = () => {
      let at = -1;
      L.forEach((o, i) => { if (onFloor(o) && can_reach({x: o.stand[0], y: o.stand[1]})) at = i; });
      return at;
    };
    let i = resume();
    if (i < 0) {
      __refresh();
      if (Math.hypot(character.x - c.x, character.y - c.y) > 30 || character.z !== 0) stop(`you're not at the ${c.course}. Add a Walk To → ${c.course} block first.`);
      i = 0;
    }
    if (onFloor(L[0]) && L[0].at[2] === 0) i = 0;    // on the ground: start at the stairs
    let lap = 0, tops = 0;
    while (lap < n) {
      const o = L[i];
      __refresh();
      if (character.hp * 100 / character.max_hp < 20) stop(`your HP is low (${character.hp}/${character.max_hp}). Eat first (and bring waterskins: the desert heat hurts).`);
      if (!onFloor(o)) {
        const j = resume();
        if (j < 0) stop(`lost the course before the ${lc(o.name)}. Walk back to the ${c.course} and run the block again.`);
        i = j; continue;
      }
      const s0 = {x: o.stand[0], y: o.stand[1], z: o.at[2]};
      if (character.x !== s0.x || character.y !== s0.y) {
        try { await smart_move(s0, {within: 0, loose: 1, doors: false, timeout: 30 * SECOND}); }
        catch (e) { const j = resume(); if (j >= 0 && j !== i) { i = j; continue; } stop(`couldn't get to the ${lc(o.name)}: ${e.message}.`); }
      }
      const ob = objAt(o);
      if (!ob) stop(`couldn't find the ${lc(o.name)} at ${o.at[0]}, ${o.at[1]}.`);
      __refresh();
      const xp0 = character.xp.agility, z0 = character.z, free0 = character.free_slots;
      __api.objectActionAt(ob.x, ob.y, o.option);
      const r = await waitFor(() => { __refresh(); return character.xp.agility > xp0 || character.z !== z0 ? "ok" : null; }, (o.top ? 6 : 20) * SECOND);
      await settle(10 * SECOND);
      __refresh();
      if (r === "ok" && character.z < z0 && !/^(enter|climb-up)$/i.test(o.option)) { game_log(`slipped at the ${lc(o.name)} and fell a floor`); const j = resume(); if (j >= 0) { i = j; continue; } }
      if (r === "ok" && o.top && character.free_slots < free0) tops++;
      if (!r && !o.top) {
        const j = resume();
        if (j >= 0 && j !== i) { i = j; continue; }      // knocked off by a moving block: carry on from where you are
        stop(`couldn't get past the ${lc(o.name)}. Check the game chat.`);
      }
      if (!r && o.top) game_log("the pyramid top is already taken this lap");
      await sleep(600);
      if (++i >= L.length) { i = 0; lap++; if (lap < n) game_log(`lap ${lap} done`); }
    }
    if (tops) game_log(`picked up ${tops} pyramid top${tops === 1 ? "" : "s"}`);
  }

  // ---- Thieving ----
  function thievingRow(target) {
    const t = (cat().skills || {}).thieving || {};
    const pp = (t.pickpocket || []).find(r => same(r.npc, target));
    if (pp) return {kind: "npc", name: pp.npc, level: pp.level};
    const st = (t.stalls || []).find(r => same(r.stall, target) || same(r.stall, target + " stall"));
    if (st) return {kind: "stall", name: st.stall, level: st.level};
    const ch = (t.chests || []).find(r => same(r.name, target) || same(r.name + " chest", target));
    if (ch) return {kind: "chest", name: ch.chest, level: ch.level, label: ch.name + " chest"};
    return null;
  }

  // Steal: pickpockets a person, steals from a stall, or searches a chest for traps
  async function steal(id, target) {
    step(id);
    let row = thievingRow(target);
    if (!row) {    // not in the list: whatever is nearby with a thieving option
      if (near(named(get_monsters("", 15), target))) row = {kind: "npc", name: target, level: 1};
      else row = {kind: "stall", name: target, level: 1};
    }
    if (levelOf("thieving") < row.level) stop(`you need Thieving level ${row.level} to steal from ${row.label || row.name}. Yours is ${levelOf("thieving")}.`);
    if (!free()) stop("your inventory is full, so there's no room for what you steal.");
    __refresh();
    if (character.hp * 100 / character.max_hp < 20) stop(`your HP is too low (${character.hp}/${character.max_hp}): getting caught could kill you. Eat first.`);
    const before = snapshot();
    if (row.kind === "npc") {
      let m = near(named(get_monsters("", 20), row.name));
      if (!m) m = await waitFor(() => near(named(get_monsters("", 20), row.name)), 5 * SECOND);
      if (!m) stop(`there is no ${row.name} nearby. Add a Walk To block to go to one first.`);
      if (!__api.npcAction(m.index, "Pickpocket")) stop(`you can't pickpocket the ${m.name}.`);
    } else {
      const opt = row.kind === "chest" ? /search for traps/i : /steal-from|steal from|steal/i;
      const find = () => near(get_objects("", 15).filter(o => (same(o.name, row.name) || norm(o.name).includes(norm(row.name))) && o.options.some(x => opt.test(x))));
      let o = find();
      if (!o && near(get_objects("", 15).filter(o => same(o.name, row.name) || norm(o.name).includes(norm(row.name))))) o = await waitFor(find, 30 * SECOND);    // empty: wait to restock
      if (!o) stop(`there is no ${row.label || row.name} nearby to steal from. Add a Walk To block to go to one first.`);
      __api.objectActionAt(o.x, o.y, o.options.find(x => opt.test(x)));
    }
    await sleep(1200);
    await idle(20 * SECOND);
    await sleep(600);
    __refresh();
    if (character.cant_move || character.locked) {    // caught and stunned: wait it out
      await waitFor(() => { __refresh(); return !character.cant_move && !character.locked; }, 10 * SECOND);
    }
  }

  // ---- Combat settings and Slayer ----
  async function set_retaliate(id, on) {
    step(id);
    __api.setRetaliate(!/^(off|no|false)$/i.test(String(on)));
    await sleep(300);
  }

  // Run: turns running on or off. Auto: on now, and again every time the energy is full, until a Run Off
  let autoRun = null;
  async function set_run(id, on, at) {
    step(id);
    if (autoRun) { clearInterval(autoRun); autoRun = null; }
    if (/^auto/i.test(String(on))) {
      const level = at === undefined || at === null || String(at).trim() === "" ? 99 : Math.max(0, Math.min(100, +at || 0));
      autoRun = setInterval(() => { if (!__api.runOn() && __api.runEnergy() >= level) __api.setRun(true); }, 1200);
      if (__api.runEnergy() >= level) __api.setRun(true);
      await sleep(300);
      return;
    }
    const want = !/^(off|no|false)$/i.test(String(on));
    __api.setRun(want);
    if (want && __api.runEnergy() < 1) game_log("run is on, but you have no run energy yet, so you'll walk until it comes back");
    await sleep(300);
  }

  function slayerTask() { return JSON.parse(__api.slayerTask()); }

  // Attack Slayer Task: fights the nearest monster of the current task
  async function attack_task(id) {
    step(id);
    const t = slayerTask();
    if (!t.task) stop("you don't have a Slayer task. Talk to a Slayer master to get one.");
    if (levelOf("slayer") < (t.level || 1)) stop(`you need Slayer level ${t.level} to hurt ${t.task}. Yours is ${levelOf("slayer")}.`);
    __refresh();
    if (character.hp * 100 / character.max_hp < 20)
      stop(`your HP is too low to fight (${character.hp}/${character.max_hp}). Add an Eat block or wait to heal first.`);
    const pick = () => near(get_monsters("", 25).filter(m => t.ids.includes(m.id) && m.hp > 0 && m.attackable && !m.in_combat));
    let m = pick();
    if (!m) m = await waitFor(pick, 15 * SECOND);
    if (!m) stop(`there are no ${t.task} nearby. Add a Walk To block to go where they are.`);
    await fight(m, m.name);
  }

  // ---- Magic ----
  const STAFF_RUNES = {air: ["air", "smoke", "mist", "dust"], water: ["water", "steam", "mud", "mist"],
                       earth: ["earth", "mud", "lava", "dust"], fire: ["fire", "steam", "lava", "smoke"]};
  function spellRow(name) {
    const sp = ((cat().skills || {}).magic || {}).spells || [];
    return sp.find(s => same(s.spell, name)) || sp.find(s => same(s.spell, name + " teleport")) || sp.find(s => norm(s.spell).startsWith(norm(name))) || null;
  }
  function spellTarget(s) {
    const n = lc(s.spell);
    if (/alchemy|superheat|^enchant|string jewellery|plank make|bake pie|superglass/.test(n)) return "item";
    if (/^charge .* orb$/.test(n)) return "object";
    if (/cure plant|fertile soil/.test(n)) return "patch";
    if (/^tele other|heal other|vengeance other|cure other|stat spy|energy transfer|boost potion|stat restore/.test(n)) return "player";
    if (s.combat || /monster examine/.test(n)) return "npc";
    return "none";
  }
  function missingRune(s) {
    const weapon = (equipment().find(e => /staff|wand|battlestaff/i.test(e.name)) || {}).name || "";
    for (const r of s.runes || []) {
      const el = (r.name.match(/^(air|water|earth|fire) rune$/i) || [])[1];
      if (el && STAFF_RUNES[lc(el)].some(w => lc(weapon).includes(w))) continue;
      const have = inv().filter(i => same(i.name, r.name)).reduce((a, i) => a + i.amount, 0);
      if (have < r.amount) return `${r.amount} ${r.name}${r.amount > 1 ? "s" : ""}`;
    }
    return "";
  }

  // Cast Spell: teleports and spells on yourself; alchemy and enchanting on an item; combat spells on a monster
  async function cast_spell(id, spell, target) {
    step(id);
    const s = spellRow(spell);
    if (!s || s.button == null) stop(`I don't know the spell ${spell}. Pick one from the Magic list.`);
    if (s.level && levelOf("magic") < s.level) stop(`you need Magic level ${s.level} to cast ${s.spell}. Yours is ${levelOf("magic")}.`);
    const book = __api.spellbook();
    if (!same(book, s.book)) stop(`${s.spell} is on the ${s.book} spellbook, but you're using the ${book} one.`);
    const miss = missingRune(s);
    if (miss) stop(`you need ${miss} to cast ${s.spell}.`);
    const kind = spellTarget(s);
    if (kind === "player") stop(`${s.spell} is cast on another player, which blocks can't do.`);
    let a = 0, b = 0, what = "";
    if (kind === "item") {
      if (!target) stop(`${s.spell} needs an item: put its name in the block.`);
      const it = inv().find(i => same(i.name, target));
      if (!it) stop(`you have no ${target} to cast ${s.spell} on.`);
      a = it.slot; what = it.name;
    } else if (kind === "npc") {
      const m = near(named(get_monsters("", 15), target || "").filter(x => x.hp > 0));
      if (!m) stop(`there is no ${target || "monster"} nearby to cast ${s.spell} on.`);
      a = m.index; what = m.name;
    } else if (kind === "object" || kind === "patch") {
      const o = kind === "patch" ? needPatch(target) : near(get_objects("", 10).filter(o => /obelisk/i.test(o.name)));
      if (!o) stop(`${s.spell} is cast on an obelisk. Stand next to the right one first.`);
      a = o.x; b = o.y; what = o.name;
    }
    __refresh(); const xp0 = character.xp.magic, place0 = character.x + "," + character.y;
    let r = __api.castSpell(s.book, s.button, kind === "patch" ? "object" : kind, a, b);
    if (r === "delay") { await sleep(1800); r = __api.castSpell(s.book, s.button, kind === "patch" ? "object" : kind, a, b); }
    if (r === "no-target") stop(`couldn't find the ${what || target} to cast on.`);
    if (r.startsWith("book:")) stop(`${s.spell} is on the ${s.book} spellbook, but you're using the ${r.slice(5)} one.`);
    if (/home teleport/i.test(s.spell)) {    // takes about 10 seconds and gives no XP
      const moved = await waitFor(() => { __refresh(); return character.x + "," + character.y !== place0 && !character.locked; }, 25 * SECOND);
      if (!moved) stop("the home teleport was interrupted or is still cooling down.");
      return;
    }
    const ok = await waitFor(() => { __refresh(); return character.xp.magic > xp0 || character.in_combat; }, 6 * SECOND);
    if (!ok) stop(`${s.spell} didn't work. Check the game chat for why.`);
    await idle(10 * SECOND);
    await sleep(600);
  }

  // ---- Prayer ----
  async function set_prayer(id, prayer, on) {
    step(id);
    const want = !/^(off|no|false)$/i.test(String(on));
    const r = __api.setPrayer(prayer, want);
    if (r === "unknown") stop(`I don't know the prayer ${prayer}. Pick one from the Prayer list.`);
    if (r.startsWith("level:")) stop(`you need Prayer level ${r.slice(6)} to use ${prayer}. Yours is ${levelOf("prayer")}.`);
    if (r === "refused") {
      __refresh();
      if (want && character.prayer < 1) stop(`you have no prayer points left. Add a Recharge → Prayer block at an altar.`);
      if (/chivalry|piety/i.test(prayer)) stop(`${cap(prayer)} needs the King's Ransom quest and Defence level ${/piety/i.test(prayer) ? 70 : 65}.`);
      stop(`you can't use ${prayer} yet. Check the game chat (a quest or Defence level may be needed).`);
    }
    await sleep(600);
  }

  // Recharge: prays at an altar for prayer points, or renews summoning points at an obelisk
  async function recharge(id, what) {
    step(id);
    const summ = /summon/i.test(what);
    const find = () => near(get_objects("", 20).filter(o => o.options.some(x => summ ? /renew-points/i.test(x) : /pray-at|pray/i.test(x))));
    const o = find();
    if (!o) stop(summ ? "there is no summoning obelisk nearby. Walk to one first." : "there is no altar nearby. Walk to a church or altar first.");
    __api.objectActionAt(o.x, o.y, o.options.find(x => summ ? /renew-points/i.test(x) : /pray-at|pray/i.test(x)));
    await sleep(1800);
    await idle(20 * SECOND);
  }

  // ---- Summoning ----
  // Summon: summons a familiar from its pouch ("Spirit wolf" or "Spirit wolf pouch")
  async function summon(id, familiar) {
    step(id);
    const ps = ((cat().skills || {}).summoning || {}).pouches || [];
    const row = ps.find(r => same(r.familiar, familiar) || same(r.pouch, familiar) || same(r.pouch, familiar + " pouch"));
    const pouch = row ? row.pouch : familiar;
    if (row && levelOf("summoning") < row.level) stop(`you need Summoning level ${row.level} to summon a ${row.familiar}. Yours is ${levelOf("summoning")}.`);
    if (__api.questState("Wolf Whistle") !== "done") stop("you must complete the Wolf Whistle quest before you can summon familiars.");
    if (!slotsWith(pouch).length) stop(`you have no ${pouch}. Make one with a Make Item block at an obelisk.`);
    if (__api.hasFamiliar()) stop("you already have a familiar out. Dismiss it first.");
    __refresh();
    if (row && row.cost && (character.levels.summoning || 0) < row.cost) stop(`you need ${row.cost} summoning points and have ${character.levels.summoning}. Add a Recharge → Summoning block at an obelisk.`);
    __api.itemAction(inv().find(i => same(i.name, pouch)).name, "Summon");
    const ok = await waitFor(() => __api.hasFamiliar(), 6 * SECOND);
    if (!ok) stop(`couldn't summon the ${row ? row.familiar : familiar}. Check the game chat.`);
  }

  // ---- Construction ----
  // Enter House: through a house portal, normally or in building mode
  async function enter_house(id, mode) {
    step(id);
    if (__api.inHouse()) return;
    const portal = near(get_objects("", 20).filter(o => same(o.name, "Portal") && o.options.some(x => /enter/i.test(x))));
    if (!portal) stop("there is no house portal nearby. Walk to the portal in the town where your house is.");
    __api.objectActionAt(portal.x, portal.y, "Enter");
    const opts = await waitFor(() => chatbox().kind === "options" || __api.inHouse(), 20 * SECOND);
    if (!opts) stop("the portal didn't let you in. Do you own a house? Buy one from an estate agent.");
    if (!__api.inHouse()) {
      const building = /build/i.test(mode || "");
      __api.pressKey(building ? "2" : "1");
      const inside = await waitFor(() => __api.inHouse(), 20 * SECOND);
      if (!inside) stop("couldn't enter the house. Check the game chat (is this the town your house is in?).");
    }
    await sleep(2400);
  }

  // Build: builds a piece of furniture on the nearest empty spot that takes it (building mode, in your house)
  async function build(id, furniture) {
    step(id);
    if (!__api.inHouse()) stop("you're not in your house. Add an Enter House → Building mode block first.");
    if (!__api.buildingMode()) stop("you're in your house but not in building mode. Leave and enter again in building mode.");
    const spots = JSON.parse(__api.hotspots(30)).sort((a, b) => a.distance - b.distance);
    const fits = spots.filter(h => h.options.some(o => same(o.name, furniture)));
    if (!fits.length) {
      if (!spots.length) stop("there's no empty spot to build on in this room. Build a room first, or remove something.");
      stop(`there's no empty spot nearby that takes a ${furniture}. Empty spots here take: ${[...new Set(spots.flatMap(h => h.options.map(o => o.name)))].slice(0, 8).join(", ")}.`);
    }
    const h = fits[0], o = h.options.find(o => same(o.name, furniture));
    if (levelOf("construction") < o.level) stop(`you need Construction level ${o.level} to build a ${o.name}. Yours is ${levelOf("construction")}.`);
    if (!slotsWith("Hammer").length) stop("you need a hammer to build.");
    const lacking = o.needs.map(n => n.match(/^(\d+) x (.*)$/)).filter(m => m && inv().filter(i => same(i.name, m[2])).reduce((a, i) => a + i.amount, 0) < +m[1]);
    if (lacking.length) stop(`to build a ${o.name} you need ${o.needs.join(", ")}.`);
    __api.objectActionAt(h.x, h.y, "Build");
    const open = await waitFor(() => { __refresh(); return character.screen === 396; }, 20 * SECOND);
    if (!open) stop(`couldn't reach the ${lc(h.spot)} spot.`);
    __api.clickButton(396, 132, 1, o.slot, -1);
    await sleep(1800);
    await idle(20 * SECOND);
    await sleep(600);
    const still = JSON.parse(__api.hotspots(30)).some(x => x.x === h.x && x.y === h.y);
    if (still) stop(`couldn't build the ${o.name}. It needs ${o.needs.join(", ")} (and nails for some). Check the game chat.`);
  }

  // Type Answer: fills an "Enter amount" or name box
  async function answer(id, value) {
    step(id);
    const m = await waitFor(() => chatbox().kind === "input", 5 * SECOND);
    if (!m) stop(`no box is asking you to type something.`);
    __api.answerInput(String(value));
    await sleep(900);
  }

  // ---- conditions ----
  function check(id, cond, value) {
    step(id);
    __refresh();
    switch (lc(cond)) {
      case "inventory is full": return character.free_slots === 0;
      case "inventory is not full": return character.free_slots > 0;
      case "inventory is empty": return character.free_slots === 28;
      case "has item": return slotsWith(value).length > 0;
      case "does not have item": return slotsWith(value).length === 0;
      case "hp below %": return character.hp * 100 / character.max_hp < (+value || 50);
      case "is in combat": return character.in_combat;
      case "is not in combat": return !character.in_combat;
      case "is at": { const p = placeFor(value); return !!p && character.z === p.z && __api.distanceTo(p.x, p.y) <= (coordsOf(value) ? 1 : 6); }
      case "bank is open": return __api.bankOpen();
      case "dialogue is open": return chatbox().kind !== "none";
      case "options are showing": return chatbox().kind === "options";
      case "make menu is open": return chatbox().kind === "make";
      case "quest is complete": return __api.questState(value) === "done";
      case "has slayer task": return !!slayerTask().task;
      case "prayer is on": return JSON.parse(__api.activePrayers()).some(n => same(n, value) || norm(n).startsWith(norm(value)));
      case "prayer points below": return character.prayer < (+value || 10);
      case "has familiar": return __api.hasFamiliar();
      case "is in house": return __api.inHouse();
      case "run is on": return __api.runOn();
      case "run is off": return !__api.runOn();
      case "run energy below": return __api.runEnergy() < (+value || 20);
      case "run energy is at least": return __api.runEnergy() >= (+value || 50);
      case "level is at least": { const m = String(value).match(/^(.*?)\s+(\d+)$/); return !!m && levelOf(m[1]) >= +m[2]; }
      case "level is": { const m = String(value).match(/^(.*?)\s+(\d+)$/); return !!m && levelOf(m[1]) === +m[2]; }
      case "level is not": { const m = String(value).match(/^(.*?)\s+(\d+)$/); return !!m && levelOf(m[1]) !== +m[2]; }
      case "level is below": { const m = String(value).match(/^(.*?)\s+(\d+)$/); return !!m && levelOf(m[1]) < +m[2]; }
      default: stop(`unknown condition "${cond}".`);
    }
  }

  // runs the whole block script and turns a BlockStop into a chat message
  function run(main) {
    main().then(() => __api.done(),
      e => {
        if (e instanceof BlockStop) __api.fail(current, e.message);
        else __api.fail(current, "something went wrong: " + (e && e.message ? e.message : e));
      });
  }

  return {run, step, walk_to, chop, mine, fish, attack: attack_monster, pick_up, eat: eat_food, open_bank, deposit, withdraw,
    close_bank, drop, wait, say: say_text, check, tick: () => sleep(0),
    use_item_on_item, use_slot_on_slot, use_item_on_target, use_slot_on_target, talk_to, press_key, wait_for, make, answer,
    catalog: cat, recipeFor, cantMake, make_item, light_fire, bury_bones,
    click_object, click_npc, craft_runes,
    lay_trap, check_traps, hunt, track, rake_patch, plant, water_patch, compost_patch, cure_patch, harvest_patch,
    run_course, steal, set_retaliate, set_run, attack_task, cast_spell, set_prayer, recharge, summon, enter_house, build};
})();
