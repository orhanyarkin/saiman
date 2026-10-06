-- Same shape as a service's runtime-role grant migration: the app role arrives as a placeholder.
GRANT SELECT, INSERT ON widget TO ${app_role};
