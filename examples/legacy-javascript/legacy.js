class LegacyService {
  load(value, items) {
    var loose = value == "1";
    if (loose != false) console.log(__dirname, __filename);

    const first = arguments[0];
    const fs = require("fs");
    const promise = new Promise(function (resolve) {
      resolve(value);
    });
    const label = "legacy " + "javascript";
    const short = label.substr(1);
    const encoded = escape(label);
    const decoded = unescape(encoded);
    const found = items.indexOf(value) >= 0;

    exports.label = label;
    module.exports = { first, fs, promise, short, decoded, found };
  }
}

var handler = function (evt) {
  return this.id + evt;
}.bind(this);

exports.helper = function (x) {
  return escape(x) + unescape(x);
};

function legacyHelpers(list, value) {
  var merged = Object.assign({}, value);
  var starts = list.indexOf(value) === 0;
  var first = String(value).charAt(0);
  var onDone = function (err, data) {
    if (err) throw err;
    return data;
  };
  return { merged, starts, first, onDone };
}
