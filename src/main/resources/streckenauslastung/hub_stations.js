// Both navigation variants are one document: `body.sidebar` shows the station list beside fully
// expanded sections, `body.collapsible` hides it and starts with all sections closed.
const sections = document.querySelectorAll('details');
function openTarget() {
  const target = document.getElementById(location.hash.slice(1));
  if (target) { target.open = true; target.scrollIntoView(); }
}
function setNavigation(mode) {
  document.body.className = mode;
  sections.forEach(s => { s.open = mode === 'sidebar'; });
  openTarget();
}
document.querySelectorAll('input[name=navigation]').forEach(radio => {
  radio.addEventListener('change', () => setNavigation(radio.value));
});
document.getElementById('filter').addEventListener('input', event => {
  const text = event.target.value.toLowerCase();
  document.querySelectorAll('nav li').forEach(li => {
    li.hidden = !li.textContent.toLowerCase().includes(text);
  });
});
addEventListener('hashchange', openTarget);
addEventListener('click', event => { if (event.target.closest('a[href^="#"]')) setTimeout(openTarget); });
setNavigation('sidebar');
