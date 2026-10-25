// Cow hunter: Fantasy Land style loop. Attacks cows, eats when low, loots its own kills, buries bones.
var settings = { target: "Cow", eat_below: 50, food: "Lobster", loot: ["Cowhide", "Bones"] };
var kills = 0, fighting = null, lootSpot = null, lootUntil = 0;

setInterval(function () {
  if (hp_percent() < settings.eat_below && has(settings.food)) { game_log("eating, hp " + character.hp); eat(settings.food); return; }
  var t = get_targeted_monster();
  if (t) { fighting = t; return; }                          // still fighting: remember where it stands
  if (fighting) {                                           // it died (or we lost it)
    kills++; set_message("kills: " + kills);
    game_log("kill #" + kills + " at " + fighting.x + "," + fighting.y);
    lootSpot = { x: fighting.x, y: fighting.y, radius: 1 }; lootUntil = __tickNo + 6; fighting = null;
  }
  if (is_busy()) return;                                   // one action at a time
  if (has("Bones")) { bury("Bones"); return; }
  if (lootSpot && __tickNo < lootUntil) { if (loot(settings.loot, lootSpot)) return; }
  var m = get_nearest_monster({ name: settings.target });
  if (m) { attack(m); game_log("attacking " + m.name + " at " + m.distance + " tiles"); }
}, 600);
