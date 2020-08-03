
#### Version Number
${version-number}

#### New Features
- **SCMOD-9780**: Updated images to use Java 11

- **SCMOD-9861**: Updated to ignore unsupported mapping changes
    In the previous version if any unsupported mapping changes were detected then the index would not be updated at all.  Following this change the changes that can be applied will be applied and only the unsupported changes will not be applied.

- **SCMOD-10383**: Added options to provide a username and password for requests to Elasticsearch.

#### Known Issues
- None

#### Breaking Changes
- **SCMOD-9861**: Updated to ignore unsupported mapping changes
