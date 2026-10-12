-- 12TestersFree: esquema inicial, separado de cualquier otra aplicación.
-- No ejecutar hasta disponer del proyecto Supabase propio.
create extension if not exists pgcrypto;

create table public.profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  display_name text not null default '',
  normal_coins integer not null default 0 check (normal_coins >= 0),
  boost_coins integer not null default 0 check (boost_coins >= 0),
  library_slots integer not null default 2 check (library_slots between 2 and 10),
  created_at timestamptz not null default now()
);
create table public.apps (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.profiles(user_id) on delete cascade,
  package_name text not null check (package_name ~ '^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$'),
  name text not null check (length(name) between 1 and 100),
  developer_name text not null default '',
  play_test_url text not null check (play_test_url ~ '^https://play\.google\.com/'),
  icon_path text,
  instructions text not null default '',
  created_at timestamptz not null default now(),
  unique(owner_id, package_name)
);
create table public.listings (
  id uuid primary key default gen_random_uuid(),
  app_id uuid not null references public.apps(id) on delete cascade,
  owner_id uuid not null references public.profiles(user_id) on delete cascade,
  published_at timestamptz not null default now(),
  expires_at timestamptz not null,
  last_boost_at timestamptz,
  boost_score integer not null default 0,
  removed_at timestamptz,
  check (expires_at > published_at)
);
create unique index listings_one_active_per_app on public.listings(app_id) where removed_at is null;
create table public.test_rewards (
  id uuid primary key default gen_random_uuid(),
  tester_id uuid not null references public.profiles(user_id) on delete cascade,
  app_id uuid not null references public.apps(id) on delete cascade,
  listing_id uuid not null references public.listings(id),
  rewarded_at timestamptz not null default now(),
  unique(tester_id, app_id)
);
create table public.private_feedback (
  tester_id uuid not null references public.profiles(user_id) on delete cascade,
  app_id uuid not null references public.apps(id) on delete cascade,
  body text not null check (length(body) <= 2000),
  updated_at timestamptz not null default now(),
  primary key(tester_id, app_id)
);
create table public.coin_ledger (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(user_id) on delete cascade,
  currency text not null check (currency in ('normal','boost')),
  amount integer not null check (amount <> 0),
  reason text not null,
  reference_id uuid,
  created_at timestamptz not null default now()
);
create table public.ad_rewards (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(user_id) on delete cascade,
  provider_transaction_id text not null unique,
  rewarded_at timestamptz not null default now()
);

alter table public.profiles enable row level security;
alter table public.apps enable row level security;
alter table public.listings enable row level security;
alter table public.test_rewards enable row level security;
alter table public.private_feedback enable row level security;
alter table public.coin_ledger enable row level security;
alter table public.ad_rewards enable row level security;

create policy profiles_read_own on public.profiles for select to authenticated using (user_id = auth.uid());
create policy apps_read on public.apps for select to authenticated using (true);
create policy listings_read on public.listings for select to authenticated using (true);
create policy feedback_read on public.private_feedback for select to authenticated using (
  tester_id = auth.uid() or exists (select 1 from public.apps a where a.id = app_id and a.owner_id = auth.uid())
);
create policy ledger_read_own on public.coin_ledger for select to authenticated using (user_id = auth.uid());
-- Sin políticas de escritura desde el cliente: todas las operaciones económicas y
-- publicaciones deben pasar por funciones transaccionales seguras en el servidor.
-- No se conceden permisos directos para acreditar monedas ni registrar recompensas.

create function public.new_profile() returns trigger language plpgsql security definer
set search_path = '' as $$
begin
  insert into public.profiles(user_id, display_name)
  values (new.id, coalesce(new.raw_user_meta_data ->> 'full_name', ''));
  return new;
end;
$$;
create trigger on_auth_user_created after insert on auth.users
for each row execute function public.new_profile();

create index apps_owner_idx on public.apps(owner_id);
create index listings_discover_idx on public.listings(expires_at, boost_score desc, published_at desc) where removed_at is null;
create index rewards_tester_idx on public.test_rewards(tester_id);
create index ledger_user_time_idx on public.coin_ledger(user_id, created_at desc);
