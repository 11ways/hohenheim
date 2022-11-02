const db_info = alchemy.settings.database?.dev;

// Create the 'default' datasource of type 'mongo'
Datasource.create('mongo', 'default', {
	host      : db_info?.host      || '127.0.0.1',
	database  : db_info?.database  || 'hohenheim-dev',
	login     : db_info?.login     || false,
	password  : db_info?.password  || false
});