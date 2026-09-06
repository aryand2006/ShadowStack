// Single-issue: var → let
var count = 0;
function bump() {
  var next = count + 1;
  count = next;
  return count;
}
