const tabs = document.querySelectorAll('nav button');

function show(agency) {
  tabs.forEach(tab => tab.classList.toggle('active', tab.textContent === agency));
  document.querySelectorAll('section').forEach(section => {
    section.hidden = section.dataset.agency !== agency;
  });
}

// The heights of the header and the section headings change with the window width, as tabs and text wrap.
const header = document.querySelector('header');
// Heights are not rounded to whole pixels, which would leave gaps between the sticky parts.
const height = element => element.getBoundingClientRect().height + 'px';
const heights = new ResizeObserver(() => {
  document.documentElement.style.setProperty('--header-height', height(header));
  document.querySelectorAll('section').forEach(section => {
    section.style.setProperty('--heading-height', height(section.querySelector('h2')));
  });
});
heights.observe(header);
document.querySelectorAll('h2').forEach(heading => heights.observe(heading));

tabs.forEach(tab => tab.addEventListener('click', () => show(tab.textContent)));
show(tabs[0].textContent);
