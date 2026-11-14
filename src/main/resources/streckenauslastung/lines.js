const tabs = document.querySelectorAll('nav button');

function show(agency) {
  tabs.forEach(tab => tab.classList.toggle('active', tab.textContent === agency));
  document.querySelectorAll('section').forEach(section => {
    section.hidden = section.dataset.agency !== agency;
  });
}

tabs.forEach(tab => tab.addEventListener('click', () => show(tab.textContent)));
show(tabs[0].textContent);
