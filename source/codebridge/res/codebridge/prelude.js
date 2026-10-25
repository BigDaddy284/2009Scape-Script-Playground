// prelude.js: Fantasy Land style helpers on top of the Java GameApi (__api).
// Runs inside GraalJS (modern JavaScript). Everything here executes on the game thread, one tick at a time.
var __timers = [], __timerId = 1, __waits = [], __tickNo = 0;
var TICK_MS = 600;

function __ticks(ms) { return Math.max(1, Math.ceil((ms || 0) / TICK_MS)); }
function setTimeout(fn, ms) { var id = __timerId++; __timers.push({id: id, fn: fn, at: __tickNo + __ticks(ms), every: 0}); return id; }
function setInterval(fn, ms) { var id = __timerId++; var t = __ticks(ms); __timers.push({id: id, fn: fn, at: __tickNo + t, every: t}); return id; }
function clearTimeout(id) { __timers = __timers.filter(function (t) { return t.id !== id; }); }
var clearInterval = clearTimeout;


// Wait until check() returns a value (not false/undefined). Resolves with that value. Rejects on timeout (ms).
function wait_until(check, timeout_ms) {
  return new Promise(function (resolve, reject) {
    __waits.push({check: check, resolve: resolve, reject: reject, until: __tickNo + __ticks(timeout_ms || 60000)});
  });
}
function sleep(ms) { var until = __tickNo + __ticks(ms); return wait_until(function () { return __tickNo >= until; }); }

// ---- logging ----
function game_log(msg) { __api.log(String(msg)); }
function log(msg) { game_log(msg); }
var console = { log: function () { game_log(Array.prototype.slice.call(arguments).join(" ")); } };
function set_message(msg) { __api.message(String(msg)); }
function say(text) { __api.say(String(text)); }

// ---- character ----
var character = {};
function __refresh() { character = JSON.parse(__api.character()); return character; }
function items() { return JSON.parse(__api.inventory()); }
function equipment() { return JSON.parse(__api.equipment()); }
function bank_items() { return JSON.parse(__api.bank()); }
function count(name) { return items().filter(function (i) { return i.name.toLowerCase() === name.toLowerCase(); }).reduce(function (a, i) { return a + i.amount; }, 0); }
function has(name) { return count(name) > 0; }
function is_moving() { return __api.moving(); }
function hp_percent() { __refresh(); return 100 * character.hp / character.max_hp; }

// ---- movement ----
var PLACES = {
  "lumbridge": {x: 3222, y: 3218, z: 0},
  "lumbridge cows": {x: 3257, y: 3266, z: 0},
  "lumbridge chickens": {x: 3233, y: 3295, z: 0},
  "varrock west bank": {x: 3185, y: 3436, z: 0},
  "varrock east bank": {x: 3253, y: 3420, z: 0},
  "varrock square": {x: 3212, y: 3424, z: 0},
  "draynor bank": {x: 3093, y: 3243, z: 0},
  "draynor willows": {x: 3087, y: 3235, z: 0},
  "barbarian village": {x: 3082, y: 3420, z: 0},
  "falador east bank": {x: 3013, y: 3355, z: 0},
  "al kharid mine": {x: 3300, y: 3300, z: 0},
  "grand exchange": {x: 3164, y: 3487, z: 0}
};
function __resolveDest(dest) {
  if (typeof dest === "string") {
    var p = PLACES[dest.toLowerCase()];
    if (p) return p;
    var m = get_nearest_monster({name: dest, radius: 30});
    if (m) return {x: m.x, y: m.y, z: m.z};
    throw new Error("smart_move: unknown place '" + dest + "'");
  }
  if (dest && dest.x !== undefined) return {x: dest.x, y: dest.y, z: dest.z || 0};
  throw new Error("smart_move: bad destination");
}
// Walks to a place name, {x,y,z} or a nearby monster name. Opens closed doors and gates on the way.
// The promise resolves on arrival and rejects if the character is stuck with no door to open.
function smart_move(dest, opts) {
  opts = opts || {};
  var d = __resolveDest(dest);
  __refresh();
  if (__sameLevel(d)) return __walk(d, opts);
  // another floor, or underground: take stairs, ladders and trapdoors on the way, then walk the rest
  return (async function () {
    var tried = {}, deadline = Date.now() + (opts.timeout || 360000);
    for (var hop = 0; hop < 8 && !__sameLevel(d); hop++) {
      if (Date.now() > deadline) throw new Error("couldn't find the way to that floor in time");
      __refresh();
      var plane = __underground(d.y) !== __underground(character.y);
      var spot = plane ? (__underground(d.y) ? {x: d.x, y: d.y - 6400} : null) : {x: d.x, y: d.y};
      // get close to the building (or above the dungeon) first, so its stairs are in sight
      if (spot && Math.hypot(character.x - spot.x, character.y - spot.y) > 12) {
        var why = "";
        await __walk({x: spot.x, y: spot.y, z: character.z}, {within: 10, loose: 40}).catch(function (e) { why = e.message; });
        __refresh();
        // on the ground and still far off (an island, a walled-off area): stairs here won't help.
        // (upstairs, the way down comes first, so going on is right)
        if (why && character.z === 0 && Math.hypot(character.x - spot.x, character.y - spot.y) > 60) throw new Error(why);
      }
      var used = await __changeLevel(d, tried, null);
      if (!used) {
        var where = __underground(d.y) && !__underground(character.y) ? "underground" : "to floor " + d.z;
        throw new Error("found no stairs or ladder to go " + where);
      }
      __refresh();
      // on the right level but a separate part of it (another building, another dungeon), or carried off somewhere
      // far away (a cave into another area): go back, try another way
      var carried = Math.hypot(character.x - d.x, (character.y % 6400) - (d.y % 6400)) > 300;
      if ((__sameLevel(d) && __api.routeGap(d.x, d.y) > 4) || carried) {
        tried[used] = true;
        game_log("that way doesn't lead there; going back");
        if (!(await __changeLevel(d, {}, plane ? (__underground(d.y) ? "up" : "down") : (d.z > used.split(",")[2] ? "down" : "up"))))
          throw new Error("took a way that doesn't lead there and couldn't get back");
      }
    }
    __refresh();
    if (!__sameLevel(d)) throw new Error("could not reach floor " + d.z);
    return __walk(d, opts);
  })();
}

// dungeons and cellars sit 6400 tiles north of the surface on the map
function __underground(y) { return y >= 6400; }
function __sameLevel(d) { __refresh(); return character.z === d.z && __underground(character.y) === __underground(d.y); }

// One step up or down (stairs, ladder, trapdoor, cave) towards d, or in direction dir ("up"/"down").
// Returns "x,y,z" of the way it took when the level changed, or false.
async function __changeLevel(d, tried, dir) {
  __refresh();
  var down, plane = __underground(d.y) !== __underground(character.y);
  if (dir) down = dir === "down"; else if (plane) down = __underground(d.y); else down = d.z < character.z;
  var near = dir ? {x: character.x, y: character.y} : plane ? {x: d.x, y: __underground(d.y) ? d.y - 6400 : d.y + 6400} : d;
  var want = down ? /^(climb-down|climb down|go-down|walk-down|climb|enter|squeeze-through|go-through)$/i : /^(climb-up|climb up|go-up|walk-up|climb|exit|leave)$/i;
  var ways = get_objects("", 60).filter(function (o) {
    return o.options.some(function (op) { return want.test(op); }) && !/door|gate|portal|tree|rock|bank|stall|altar/i.test(o.name)
      && !tried[o.x + "," + o.y + "," + character.z];
  });
  if (!plane) ways = ways.filter(function (o) { return o.options.some(function (op) { return (down ? /down/i : /up/i).test(op) || /^climb$/i.test(op); }); });
  if (!ways.length) return false;
  // close to the target: the way nearest to it. Far away: the nearest way from here.
  var farAway = Math.hypot(character.x - near.x, character.y - near.y) > 40;
  ways.sort(function (a, b) {
    if (farAway) return a.distance - b.distance;
    // between floors, stairs before ladders: tower ladders often lead to a roof of their own
    var pen = function (o) { return !plane && /ladder/i.test(o.name) ? 12 : 0; };
    var da = Math.hypot(a.x - near.x, a.y - near.y) + pen(a), db = Math.hypot(b.x - near.x, b.y - near.y) + pen(b);
    return (da + a.distance * 0.25) - (db + b.distance * 0.25);
  });
  var startZ = character.z, startUnder = __underground(character.y);
  for (var i = 0; i < Math.min(3, ways.length); i++) {
    var o = ways[i];
    var opt = o.options.find(function (op) { return (down ? /down/i : /up/i).test(op) && want.test(op); })
           || o.options.find(function (op) { return want.test(op); });
    try { await __walk({x: o.x, y: o.y, z: character.z}, {within: 2, timeout: 90000}); } catch (e) { /* the click walks the last steps */ }
    game_log((down ? "going down " : "going up ") + o.name.toLowerCase() + " at " + o.x + "," + o.y);
    __api.objectActionAt(o.x, o.y, opt);
    var changed = await wait_until(function () {
      __refresh();
      if (character.z !== startZ || __underground(character.y) !== startUnder) return true;
      var c = JSON.parse(__api.chatbox());
      if (c.kind === "options") __api.pressKey(down ? "2" : "1");      // "Climb up / Climb down"
      return false;
    }, 12000).catch(function () { return false; });
    if (changed) { await sleep(1200); return o.x + "," + o.y + "," + startZ; }
  }
  return false;
}

function __walk(d, opts) {
  var near = opts.within !== undefined ? opts.within : 2, last = null, still = 0, doors = 0;
  __refresh();
  var far = __api.distanceTo(d.x, d.y);
  return wait_until(function () {
    __refresh();
    var dist = __api.distanceTo(d.x, d.y);
    if (character.z === d.z && dist <= near) return {x: character.x, y: character.y};
    if (!character.moving && !character.busy) {          // only issue a new walk when the last one has finished
      var here = character.x + "," + character.y;
      still = (here === last) ? still + 1 : 0; last = here;
      if (still >= 2 && opts.doors !== false && doors < 20) {   // not getting closer: is a door or gate in the way?
        var door = blocking_door(d);
        if (door) { doors++; still = 0; open_door(door); return false; }
      }
      if (still > 4 && opts.loose && dist <= opts.loose && character.z === d.z) return {x: character.x, y: character.y};   // as close as it gets
      if (still > 4) throw new Error("stuck at " + here + " (" + Math.round(dist) + " tiles from target)");
      __api.walkTowards(d.x, d.y, d.z);
    }
    return false;
  }, opts.timeout || Math.max(300000, far * 1500));
}

// ---- doors & gates ----
// The closed door or gate between you and a place or monster, or null if the way is clear.
function blocking_door(target) {
  var r = JSON.parse(__api.pathCheck(target.x, target.y, target.index !== undefined ? target.index : -1));
  return r && !r.reachable ? r.door : null;
}
// Can you walk to this place or monster without opening anything?
function can_reach(target) {
  var r = JSON.parse(__api.pathCheck(target.x, target.y, target.index !== undefined ? target.index : -1));
  return !!(r && r.reachable);
}
// Walks to a door or gate and opens it.
function open_door(door) {
  var opt = door.option || "Open";
  game_log((opt === "Open" ? "opening " : opt.toLowerCase().replace("-", " ") + " ") + door.name.toLowerCase() + " at " + door.x + "," + door.y);
  return __api.objectActionAt(door.x, door.y, opt);
}
function move(x, y) { return smart_move({x: x, y: y, z: character.z}, {within: 0}); }
function stop_moving() { __api.stopMoving(); }
function teleport(x, y, z) { __api.teleport(x, y, z || 0); }

// ---- monsters & combat ----
function get_monsters(name, radius) { return JSON.parse(__api.npcs(name || "", radius || 16)); }
// opts: name, max_level, min_level, radius, no_target (skip ones already fighting)
function get_nearest_monster(opts) {
  opts = opts || {};
  var list = get_monsters(opts.name, opts.radius || 16).filter(function (m) {
    if (!m.attackable && opts.attackable !== false) return false;
    if (opts.max_level !== undefined && m.level > opts.max_level) return false;
    if (opts.min_level !== undefined && m.level < opts.min_level) return false;
    if (opts.no_target !== false && m.in_combat && m.index !== character.target) return false;
    return true;
  });
  list.sort(function (a, b) { return a.distance - b.distance; });
  return list[0] || null;
}
function get_targeted_monster() {
  __refresh();
  if (character.target < 0) return null;
  var all = get_monsters("", 20).filter(function (m) { return m.index === character.target; });
  return all[0] || null;
}
// Attacks a monster. If a closed door or gate is in the way it opens that first (call attack again once
// is_busy() is false). Returns true when the attack started, "door" when it went to open a door.
function attack(m) {
  if (!m) return false;
  var door = blocking_door(m);
  if (door) { open_door(door); return "door"; }
  return __api.attack(m.index);
}
function in_combat() { __refresh(); return character.in_combat; }

// ---- items ----
function use_item(name, option) { return __api.itemAction(name, option); }
function eat(name) { return __api.itemAction(name, "Eat"); }
function drink(name) { return __api.itemAction(name, "Drink"); }
function bury(name) { return __api.itemAction(name || "Bones", "Bury"); }
function equip(name) { var r = __api.itemAction(name, "Wield"); return r.indexOf("ok") === 0 ? r : __api.itemAction(name, "Wear"); }
function ground_items(radius) { return JSON.parse(__api.groundItems(radius || 10)); }
// loot(names, near): pick up the nearest ground item. names = list of item names (optional),
// near = {x, y, radius} to only take items around a spot, e.g. where your last kill died.
function loot(names, near) {
  if (is_busy()) return false;
  var list = ground_items(near ? (near.radius || 1) + 15 : 10);
  if (names) list = list.filter(function (g) { return names.some(function (n) { return n.toLowerCase() === g.name.toLowerCase(); }); });
  if (near) list = list.filter(function (g) { return Math.abs(g.x - near.x) <= (near.radius || 1) && Math.abs(g.y - near.y) <= (near.radius || 1); });
  if (!list.length) return false;
  list.sort(function (a, b) { return __api.distanceTo(a.x, a.y) - __api.distanceTo(b.x, b.y); });
  return __api.pickUp(list[0].id, list[0].x, list[0].y);
}
// true while walking or while an action (pick up, chop, smelt, bank...) is in progress. Don't start another action then.
function is_busy() { __refresh(); return character.moving || character.busy; }

// ---- objects & npcs ----
function get_objects(name, radius) { return JSON.parse(__api.objects(name, radius || 20)); }
// interact_at(obj, option): click an option on one specific object from get_objects()
function interact_at(obj, option) { return __api.objectActionAt(obj.x, obj.y, option); }
// nearest rock that gives this ore, e.g. get_rock("Copper ore")
function get_rock(ore, radius) {
  var list = get_objects("Rocks", radius || 20).filter(function (r) { return r.ore && r.ore.toLowerCase() === ore.toLowerCase(); });
  list.sort(function (a, b) { return a.distance - b.distance; });
  return list[0] || null;
}
function interface_open() { return __api.interfaceOpen(); }
function interact(objectName, option) { return __api.objectAction(objectName, option); }
function talk_to(m, option) { return __api.npcAction(m.index, option || "Talk-to"); }
function use_on(itemName, objectName) { return __api.useItemOnObject(itemName, objectName); }
// Resolves once the character stops moving and has no action running (or after timeout).
function until_idle(timeout_ms) {
  var seenBusy = false, quiet = 0;
  return wait_until(function () {
    __refresh();
    var busy = character.busy || character.moving;
    if (busy) { seenBusy = true; quiet = 0; return false; }
    return (++quiet >= 3) ? true : false;
  }, timeout_ms || 120000);
}

// ---- bank ----
function bank_deposit_all() { __api.bankDepositAll(); return until_idle(); }
function bank_withdraw(name, amount) { __api.bankWithdraw(name, amount || 1); }

// ---- engine hooks ----
function __tick() {
  __tickNo++;
  __refresh();
  var due = __timers.filter(function (t) { return t.at <= __tickNo; });
  due.forEach(function (t) {
    if (t.every) t.at = __tickNo + t.every; else clearTimeout(t.id);
    try { t.fn(); } catch (e) { game_log("error: " + __where(e)); }
  });
  var waits = __waits; __waits = [];
  waits.forEach(function (w) {
    var v;
    try { v = w.check(); } catch (e) { w.reject(e); return; }
    if (v) w.resolve(v);
    else if (__tickNo >= w.until) w.reject(new Error("timed out"));
    else __waits.push(w);
  });
}
// "TypeError: x is not a function (my_script.js line 12)"
function __where(e) {
  var m = e && e.stack ? /\(?([^()\s]+\.js):(\d+)/.exec(e.stack.split("\n").filter(function (l) { return l.indexOf("prelude.js") < 0; }).slice(1).join("\n")) : null;
  return String(e) + (m ? " (" + m[1] + " line " + m[2] + ")" : "");
}
// Errors inside async functions and promise chains nobody caught still reach the log.
if (typeof Graal !== "undefined" && Graal.setUnhandledPromiseRejectionHandler)
  Graal.setUnhandledPromiseRejectionHandler(function (e) { game_log("error: " + __where(e)); });

function __stop() { __timers = []; __waits = []; __api.stopMoving(); }
function __evalToString(code) {
  var r = (0, eval)(code);
  try { return (typeof r === "object") ? JSON.stringify(r) : String(r); } catch (e) { return String(r); }
}
