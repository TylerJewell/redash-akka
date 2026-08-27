// Where the seeded data source points. redash's own suite runs inside a compose network
// where the database answers to `postgres`; this rebuild is driven from the host, where the
// same database answers to whatever docker published it on. The address is deployment
// configuration rather than behaviour, so it is read from the environment with redash's own
// values as the default — nothing else in this file or in any spec is changed.
const DB_HOST = process.env.CYPRESS_DB_HOST || "postgres";
const DB_PORT = parseInt(process.env.CYPRESS_DB_PORT || "5432", 10);

exports.seedData = [
  {
    route: "/setup",
    type: "form",
    data: {
      name: "Example Admin",
      email: "admin@redash.io",
      password: "password",
      org_name: "Redash",
    },
  },
  {
    route: "/login",
    type: "form",
    data: {
      email: "admin@redash.io",
      password: "password",
    },
  },
  {
    route: "/api/data_sources",
    type: "json",
    data: {
      name: "Test PostgreSQL",
      options: {
        dbname: "postgres",
        host: DB_HOST,
        port: DB_PORT,
        sslmode: "prefer",
        user: "postgres",
      },
      type: "pg",
    },
  },
  {
    route: "/api/destinations",
    type: "json",
    data: {
      name: "Test Email Destination",
      options: {
        addresses: "test@example.com",
      },
      type: "email",
    },
  },
];
