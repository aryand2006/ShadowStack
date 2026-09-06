// Single-issue: Object.assign({}, x) → ({...x})
function merge(value) {
  return Object.assign({}, value);
}
