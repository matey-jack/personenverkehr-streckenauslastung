document.getElementById('filter').addEventListener('input', event => {
  const text = event.target.value.toLowerCase();
  document.querySelectorAll('nav li').forEach(li => {
    li.hidden = !li.textContent.toLowerCase().includes(text);
  });
});
