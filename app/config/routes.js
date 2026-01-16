Router.add({
	name       : 'Home',
	methods    : 'get',
	paths      : '/',
	handler    : 'Static#home',
	breadcrumb : 'static.home',
});

// DEV ONLY: Auto-login route
if (alchemy.environment == 'dev') {
	Router.add({
		name    : 'Static#devLogin',
		methods : 'get',
		paths   : '/dev/login',
	});
}

Router.add({
	name       : 'sitestat-start',
	methods    : 'post',
	paths      : '/api/sitestat/start',
	handler    : 'Static#sitestatStart',
	permission : 'hohenheim.site.start',
});

Router.add({
	name       : 'sitestat-kill',
	methods    : 'get',
	paths      : '/api/sitestat/kill',
	handler    : 'Static#sitestatKill',
	permission : 'hohenheim.site.kill',
});

Router.add({
	name       : 'sitestat-isolate',
	methods    : 'get',
	paths      : '/api/sitestat/isolate',
	handler    : 'Static#sitestatIsolate',
	permission : 'hohenheim.site.isolate',
});

Router.add({
	name       : 'sitestat-log',
	methods    : 'get',
	paths      : '/api/sitestat/log',
	handler    : 'Static#sitestatLog',
	permission : 'hohenheim.site.log',
});

Router.add({
	name       : 'sitestat-logs',
	methods    : 'get',
	paths      : '/api/sitestat/logs',
	handler    : 'Static#sitestatLogs',
	permission : 'hohenheim.site.log',
});

Router.add({
	name       : 'sitestat',
	methods    : 'get',
	paths      : '/api/sitestat',
	handler    : 'Static#sitestat',
	permission : 'hohenheim.site.stats',
});

Router.linkup('Terminal::linkup', 'terminallink', 'Static#terminal');

alchemy.plugins.chimera.sidebar_menu = [
	{
		model : 'Site',
		title : 'Sites'
	},
	{
		model : 'System.Task',
		title : 'Tasks'
	},
	{
		model : 'Microcopy',
		title : 'Microcopy'
	},
	{
		model : 'Acl.PermissionGroup',
		title : 'Permission Groups',
	},
	{
		model : 'ProteusRealm',
		title : 'Proteus Realms',
	},
	{
		model : 'ProteusSiteUser',
		title : 'Proteus Site Users',
	},
	{
		model : 'User',
		title : 'Users'
	},
];