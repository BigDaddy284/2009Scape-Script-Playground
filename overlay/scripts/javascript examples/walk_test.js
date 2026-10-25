// Long walk: Lumbridge -> Varrock west bank -> Barbarian Village -> Lumbridge, timing each leg.
var legs = ["varrock west bank", "barbarian village", "lumbridge"], i = 0, t0 = __tickNo;
function next() {
  if (i >= legs.length) { game_log("all legs done"); return; }
  var dest = legs[i++]; t0 = __tickNo; game_log("walking to " + dest + " from " + character.x + "," + character.y);
  smart_move(dest).then(function (p) { game_log("arrived " + dest + " in " + (__tickNo - t0) + " ticks"); next(); },
                        function (e) { game_log("FAILED " + dest + ": " + e.message); });
}
next();
