// Industry extras: optional catch binding + nullable chaining candidates
function readProp(obj) {
  try {
    return obj && obj.value;
  } catch (err) {
    return undefined;
  }
}

var unusedCatchDemo = function () {
  try {
    throw new Error("x");
  } catch (e) {
    return "swallowed";
  }
};
