// Varrock east mine: mine copper and tin alternately until 14 ores, then report.
var want = ["Copper ore", "Tin ore"], i = 0;
setInterval(function () {
  if (count("Copper ore") + count("Tin ore") >= 14) { game_log("done: " + count("Copper ore") + " copper, " + count("Tin ore") + " tin"); set_message("full"); clearInterval(1); return; }
  if (is_busy()) return;
  var ore = count("Copper ore") <= count("Tin ore") ? "Copper ore" : "Tin ore";
  var rock = get_rock(ore);
  if (!rock) { game_log("no rock for " + ore); return; }
  interact_at(rock, "Mine");
}, 600);
