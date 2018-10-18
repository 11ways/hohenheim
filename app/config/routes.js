Router.add({
	name       : 'Home',
	methods    : 'get',
	paths      : '/',
	handler    : 'Static#home',
	breadcrumb : 'static.home'
});

Router.add({
	name       : 'sitestat-start',
	methods    : 'get',
	paths      : '/api/sitestat/start',
	handler    : 'Static#sitestatStart',
});

Router.add({
	name       : 'sitestat-kill',
	methods    : 'get',
	paths      : '/api/sitestat/kill',
	handler    : 'Static#sitestatKill',
});

Router.add({
	name       : 'sitestat-log',
	methods    : 'get',
	paths      : '/api/sitestat/log',
	handler    : 'Static#sitestatLog',
});

Router.add({
	name       : 'sitestat-logs',
	methods    : 'get',
	paths      : '/api/sitestat/logs',
	handler    : 'Static#sitestatLogs',
});

Router.add({
	name       : 'sitestat',
	methods    : 'get',
	paths      : '/api/sitestat',
	handler    : 'Static#sitestat',
});

Router.linkup('Terminal::linkup', 'terminallink', 'Static#terminal');

// Add "Sites" menu item
alchemy.plugins.chimera.menu.set('site', {
	title: 'Sites',
	route: 'chimera@ModelAction',
	parameters: {
		controller: 'editor',
		subject: 'site',
		action: 'index'
	},
	icon: {
		fa: 'globe-africa'
	}
});

// Add "Domains" menu item
alchemy.plugins.chimera.menu.set('domain', {
	title: 'Domains',
	route: 'chimera@ModelAction',
	parameters: {
		controller: 'editor',
		subject: 'domain',
		action: 'index'
	},
	icon: {
		fa: 'passport'
	}
});
