document.getElementById('filter').addEventListener('input', event => {
  const text = event.target.value.toLowerCase();
  document.querySelectorAll('nav li').forEach(li => {
    li.hidden = !li.textContent.toLowerCase().includes(text);
  });
});

// The height of a section heading changes with the window width, as its text wraps. Heights are not
// rounded to whole pixels, which would leave a gap below the heading.
const headings = new ResizeObserver(entries => {
  for (const entry of entries) {
    const height = entry.target.getBoundingClientRect().height;
    entry.target.parentElement.style.setProperty('--heading-height', height + 'px');
  }
});
document.querySelectorAll('h2').forEach(heading => {
  headings.observe(heading);
  // A click on the heading, which may be sticking far down its section, scrolls back to the top of the section.
  heading.addEventListener('click', () => heading.parentElement.scrollIntoView());
});
