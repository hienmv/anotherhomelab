-- Fictional artists and events. Hot concerts have small inventory to demo sell-outs.
insert into concerts (title, artist, venue, starts_at, price_cents, total_tickets, available, hot) values
  ('Neon Skyline World Tour', 'Aurora Vale',        'Tokyo Dome',             '2026-12-20 18:00+09', 1200000, 200,   5, true),
  ('Midnight Echoes Live',    'The Paper Comets',   'Saitama Super Arena',    '2027-01-15 19:00+09',  980000, 300, 120, true),
  ('Golden Hour Finale',      'Lumen & The Tides',  'Nippon Budokan',         '2027-02-02 18:30+09', 1500000, 150,   0, true),
  ('Jazz by the Bay',         'Kai Morita Quartet', 'Blue Note Yokohama',     '2027-01-08 20:00+09',  650000, 120, 120, false),
  ('Indie Spring Fest',       'Various Artists',    'Zepp Haneda',            '2027-03-21 16:00+09',  550000, 800, 800, false),
  ('Symphony of Lights',      'Osaka City Phil',    'Festival Hall Osaka',    '2027-02-14 19:00+09',  720000, 600, 600, false),
  ('Retro Synth Night',       'Pixel Hearts',       'Shibuya O-East',         '2026-12-05 19:30+09',  480000, 400, 400, false);
