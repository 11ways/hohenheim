const db_info = alchemy.settings.database?.live;

// Create the 'default' datasource of type 'mongo'
Datasource.create('mongo', 'default', {
	host      : db_info?.host      || '127.0.0.1',
	database  : db_info?.database  || 'hohenheim-live',
	login     : db_info?.login     || false,
	password  : db_info?.password  || false,
	port      : db_info?.port      || 27017
});
