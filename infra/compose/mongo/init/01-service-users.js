// One database + one user per service ("database per service" on a shared local server).
// The mongo image runs this as root only when the data volume is empty. It is idempotent, so it can
// also be re-run by hand on an existing volume:
//   MSYS_NO_PATHCONV=1 docker exec myplatform-mongodb-1 sh -c 'mongosh -u root -p "$MONGO_INITDB_ROOT_PASSWORD" /docker-entrypoint-initdb.d/01-service-users.js'

// ensureServiceUser(name, password): user and database share the name; readWrite on that database only.
function ensureServiceUser(name, password) {
  if (!password) {
    throw new Error(`ensureServiceUser: empty password for '${name}'`);
  }
  print(`Ensuring user and database '${name}'`);
  const target = db.getSiblingDB(name);
  if (target.getUser(name)) {
    target.updateUser(name, { pwd: password });
  } else {
    target.createUser({ user: name, pwd: password, roles: [{ role: 'readWrite', db: name }] });
  }
}

ensureServiceUser('notification', process.env.NOTIFICATION_DB_PASSWORD);
