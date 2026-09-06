// Single-issue: escape/unescape → encodeURI/decodeURI
function roundTrip(label) {
  return unescape(escape(label));
}
