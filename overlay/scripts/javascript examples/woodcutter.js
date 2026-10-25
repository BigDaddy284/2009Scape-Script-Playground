// Draynor willow cutter: chop until the inventory is full, bank at Draynor, come back. State machine style.
var settings = { tree: "Willow", spot: { x: 3087, y: 3235, z: 0 }, bank: "draynor bank", keep: ["Rune axe"] };
var state = "chop", trips = 0;

setInterval(function () {
  if (state === "walking") return;                      // a smart_move promise is in flight
  if (state === "chop") {
    if (character.free_slots === 0) { state = "to_bank"; return; }
    if (is_busy()) return;
    var r = interact(settings.tree, "Chop down");
    if (r !== "ok") { game_log("no " + settings.tree + " nearby (" + r + "), walking to spot"); go(settings.spot, "chop"); }
    return;
  }
  if (state === "to_bank") { go(settings.bank, "deposit"); return; }
  if (state === "deposit") {
    state = "walking";
    bank_deposit_all().then(function () {
      trips++; set_message("trips: " + trips + ", logs in bank: " + count_bank("Willow logs"));
      game_log("banked, trip " + trips); go(settings.spot, "chop");
    });
  }
}, 600);

function go(dest, next) {
  state = "walking";
  smart_move(dest).then(function () { state = next; }, function (e) { game_log("walk failed: " + e.message); state = next; });
}
function count_bank(name) { return bank_items().filter(function (i) { return i.name === name; }).reduce(function (a, i) { return a + i.amount; }, 0); }
