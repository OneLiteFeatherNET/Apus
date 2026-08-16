<template>
  <AppShell variant="side">
    <template #nav>
      <LayoutConsoleSidebar />
    </template>
    <template #header>
      <LayoutConsoleHeader />
    </template>
    <slot />
  </AppShell>
</template>
